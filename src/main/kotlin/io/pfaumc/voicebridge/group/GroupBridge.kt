package io.pfaumc.voicebridge.group

import de.maxhenkel.voicechat.api.Group as SvcGroup
import de.maxhenkel.voicechat.api.VoicechatConnection
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel
import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgeMod
import io.pfaumc.voicebridge.adapter.PvAdapter
import io.pfaumc.voicebridge.session.ModType
import org.slf4j.LoggerFactory
import su.plo.voice.api.server.audio.capture.PlayerActivationInfo
import su.plo.voice.api.server.audio.line.BaseServerSourceLine
import su.plo.voice.api.server.audio.source.ServerBroadcastSource
import su.plo.voice.api.server.audio.source.ServerPlayerSource
import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.groups.GroupsAddon
import su.plo.voice.groups.group.Group as PvGroup
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges SVC groups with Plasmo Voice group channels provided by the official
 * [pv-addon-groups](https://modrinth.com/plugin/pv-addon-groups) server addon.
 *
 * A group is "bridged" when an SVC group and a PV group share the same name
 * (case-insensitive). Matching is resolved live at event time on both sides ?? * no member lists are cached.
 *
 * SVC group members are also mirrored onto the PV group's roster (playersIds +
 * playerSet + the groups line's player list) so PV clients display them as group
 * members with speaking icons.
 *
 * Audio:
 * - SVC group member speaks: SVC delivers to SVC members natively; this bridge
 *   relays the encrypted frame to the matching PV group's online members. Same-
 *   dimension members receive it through a player source (PlayerSourceInfo, which
 *   PV clients index per player -> name-tag mic icon + overlay attribution);
 *   members in other dimensions receive it through a broadcast source
 *   (non-positional, cross-dimension, no positional icon ??same as the groups
 *   addon's own delivery).
 * - PV group member speaks (their "Groups" activation): PV delivers to PV members
 *   natively; this bridge delivers the decrypted frame to each SVC group member's
 *   connection via a non-positional static audio channel.
 *
 * Requires the groups addon at runtime; otherwise this bridge stays inactive.
 */
class GroupBridge(private val mod: VoiceBridgeMod) {

    private val logger = LoggerFactory.getLogger("VoiceBridge-Groups")

    private val detected: Boolean = runCatching {
        Class.forName("su.plo.voice.groups.GroupsAddon")
        true
    }.getOrDefault(false)

    private class GroupSources(
        val entity: net.minecraft.server.level.ServerPlayer,
        val level: net.minecraft.server.level.ServerLevel,
        val pvGroupId: UUID,
        val playerSource: ServerPlayerSource?,
        val broadcastSource: ServerBroadcastSource
    )

    private val svcGroupSources = ConcurrentHashMap<UUID, GroupSources>()

    private val staticChannels = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, StaticAudioChannel>>()

    /** SVC players currently mirrored onto a PV group roster (uuid -> bridged group name). */
    private val svcRoster = ConcurrentHashMap<UUID, String>()

    private val groupSequenceNumbers = ConcurrentHashMap<UUID, Long>()

    /** Throttled per-speaker diagnostics: "direction-uuid" -> lastLogMillis. */
    private val lastGroupLog = ConcurrentHashMap<String, Long>()

    private fun shouldLogGroupFrame(speakerUuid: UUID, direction: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastGroupLog["$direction-$speakerUuid"] ?: 0L
        return if (now - last >= 5000L) {
            lastGroupLog["$direction-$speakerUuid"] = now
            true
        } else false
    }

    private fun pvAddon(): GroupsAddon? {
        if (!detected) return null
        if (!mod.bridgeConfig.groupsEnabled) return null
        return runCatching {
            val container = PvAdapter.instance?.voiceServer?.addonManager?.getAddon(ADDON_ID)?.orElse(null) ?: return null
            val addon = container.getInstance().orElse(null) as? GroupsAddon ?: return null
            // Accessing groupManager throws while the groups addon has not initialized yet ??            // the outer runCatching turns that into "feature not ready".
            addon.groupManager
            addon
        }.getOrNull()
    }

    fun isAvailable(): Boolean = pvAddon() != null

    /** The PV group the player is currently in, or null. Resolved live from the groups addon. */
    fun getSpeakerPvGroupId(playerUuid: UUID): UUID? =
        pvAddon()?.groupManager?.groupByPlayer?.get(playerUuid)?.id

    /** Whether the listener is on the roster of the PV group with this id. */
    fun isListenerInPvGroup(listenerUuid: UUID, pvGroupId: UUID): Boolean =
        pvAddon()?.groupManager?.groupByPlayer?.get(listenerUuid)?.id == pvGroupId

    /** Whether an SVC group with this name exists (i.e. the PV group is bridged). */
    fun hasBridgedSvcGroup(name: String): Boolean =
        svcApi()?.groups?.any { it.name.equals(name, ignoreCase = true) } ?: false

    private fun groupsSourceLine(addon: GroupsAddon): BaseServerSourceLine? =
        runCatching { addon.groupManager.sourceLine }.getOrNull()

    private fun findPvGroup(addon: GroupsAddon, name: String): PvGroup? =
        addon.groupManager.groups.values
            .filter { it.name.equals(name, ignoreCase = true) }
            .firstOrNull { it.onlinePlayers.isNotEmpty() }

    private fun svcApi() = mod.audioRelay.svcAdapter?.voicechatApi

    private fun svcMembersOf(svcGroup: SvcGroup, api: de.maxhenkel.voicechat.api.VoicechatServerApi): List<VoicechatConnection> =
        mod.sessionManager.getAllSessions()
            .asSequence()
            .filter { it.hasModType(ModType.SIMPLE_VOICE_CHAT) }
            .mapNotNull { api.getConnectionOf(it.playerUuid) }
            .filter { it.group?.id == svcGroup.id }
            .toList()

    // ------------------------------------------------------------------
    // SVC group member speaks -> PV group members
    // ------------------------------------------------------------------

    fun relaySvcGroupToPv(speakerUuid: UUID, speakerPlayer: net.minecraft.server.level.ServerPlayer, svcGroup: SvcGroup, opusData: ByteArray) {
        val addon = pvAddon() ?: return
        val line = groupsSourceLine(addon) ?: return
        val pvAdapter = PvAdapter.instance ?: return

        val pvGroup = findPvGroupForSpeaker(addon, speakerUuid, svcGroup.name) ?: return
        val members = pvGroup.onlinePlayers.toList()
        if (members.isEmpty()) return

        lazyRosterAdd(pvAdapter, addon, pvGroup, svcGroup.name, speakerUuid, speakerPlayer)

        val encrypted = try {
            pvAdapter.voiceServer.defaultEncryption.encrypt(opusData)
        } catch (e: Exception) {
            logger.debug("Group SVC->PV: failed to encrypt audio: ${e.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
            return
        }

        val speakerLevel = runCatching { speakerPlayer.serverLevel() }.getOrNull() ?: return
        var entry = svcGroupSources[speakerUuid]
            ?.takeIf { it.entity === speakerPlayer && it.level === speakerLevel && it.pvGroupId == pvGroup.id }
        if (entry == null) {
            removeGroupSources(speakerUuid)
            entry = createGroupSources(line, pvAdapter, pvGroup, speakerUuid, speakerPlayer, speakerLevel)
            svcGroupSources[speakerUuid] = entry
        }

        val seq = groupSequenceNumbers.merge(speakerUuid, 1L) { current, _ -> current + 1 } ?: 1L
        val speakerVoice = pvAdapter.voiceServer.playerManager.getPlayerByInstance(speakerPlayer)
        val thresholdSq = mod.bridgeConfig.defaultDistance * mod.bridgeConfig.defaultDistance

        // Near members: player source (PlayerSourceInfo -> client-side speaker attribution:
        // name-tag mic icon + overlay entry). Positional, but they are within voice range.
        entry.playerSource?.let { ps ->
            try {
                ps.sendAudioFrame(encrypted, seq, Short.MAX_VALUE)
                if (shouldLogGroupFrame(speakerUuid, "svc-pv")) {
                    val near = members.count { isSameDimension(it, speakerLevel) && isWithinSpeakerRange(it, speakerVoice, thresholdSq) }
                    logger.info("Group SVC->PV: relaying $speakerUuid (playerSource=true, near=$near, far=${members.size - near}, dim=${speakerLevel.dimension().location()})")
                }
                BridgeMetrics.svcToPlasmoFrames.incrementAndGet()
            } catch (t: Throwable) {
                logger.debug("Group SVC->PV: player-source send failed for $speakerUuid: ${t.message}")
                BridgeMetrics.droppedFrames.incrementAndGet()
            }
        }

        // Far / cross-dimension members: broadcast source (non-positional, full volume, no
        // name-tag icon - same as the groups addon's own cross-dimension delivery). The
        // source filter decides the split per delivery, so members walking away switch over
        // automatically.
        entry.broadcastSource.players = members
        val sourcePacket = SourceAudioPacket(seq, entry.broadcastSource.state.toByte(), encrypted, entry.broadcastSource.id, 0)
        val speakerPacket = PlayerAudioPacket(seq, encrypted, pvGroup.id, 0, false)
        try {
            entry.broadcastSource.sendAudioPacket(sourcePacket, PlayerActivationInfo(speakerVoice, speakerPacket))
            if (shouldLogGroupFrame(speakerUuid, "svc-pv-bc")) {
                logger.info("Group SVC->PV: broadcast frame sent (listeners=${entry.broadcastSource.players?.size})")
            }
            BridgeMetrics.svcToPlasmoFrames.incrementAndGet()
        } catch (t: Throwable) {
            logger.debug("Group SVC->PV: broadcast send failed for $speakerUuid: ${t.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
        }
    }

    private fun findPvGroupForSpeaker(addon: GroupsAddon, speakerUuid: UUID, name: String): PvGroup? =
        addon.groupManager.groupByPlayer[speakerUuid]
            ?.takeIf { it.name.equals(name, ignoreCase = true) }
            ?: findPvGroup(addon, name)

    private fun createGroupSources(
        line: BaseServerSourceLine,
        pvAdapter: PvAdapter,
        pvGroup: PvGroup,
        speakerUuid: UUID,
        speakerPlayer: net.minecraft.server.level.ServerPlayer,
        speakerLevel: net.minecraft.server.level.ServerLevel
    ): GroupSources {
        val speakerVoice = pvAdapter.voiceServer.playerManager.getPlayerByInstance(speakerPlayer)
        val thresholdSq = mod.bridgeConfig.defaultDistance * mod.bridgeConfig.defaultDistance

        // Listener gates shared by both sources: group membership (privacy - the sources
        // themselves must never leak group audio to non-members) and dual-mod exclusion
        // (dual-mod listeners already receive group audio natively through their own mod).
        fun isEligibleListener(vp: VoicePlayer): Boolean =
            pvGroup.playersIds.contains(vp.instance.uuid) && !mod.sessionManager.isDualMod(vp.instance.uuid)

        // createPlayerSource exists on the runtime line class but not on the 2.1.8 API
        // interface this jar compiles against -> reflection, graceful null on older PV.
        val playerSource = runCatching {
            line.javaClass.methods.firstOrNull { it.name == "createPlayerSource" }
                ?.invoke(line, speakerVoice, false, null, null) as? ServerPlayerSource
        }.getOrNull()?.apply {
            isIconVisible = true
            addFilter<VoicePlayer> { vp ->
                isEligibleListener(vp) && isSameDimension(vp, speakerLevel) && isWithinSpeakerRange(vp, speakerVoice, thresholdSq)
            }
        }

        val broadcast = line.createBroadcastSource().also { source ->
            source.sender = speakerVoice
            source.isIconVisible = true
            // Camera-relative at zero offset: the audio anchors to each listener's camera,
            // so it plays at full volume regardless of distance AND dimension (a plain
            // positional broadcast would fade out with distance to the speaker's coords).
            source.isCameraRelative = true
            source.relativePosition = su.plo.slib.api.position.Pos3d(0.0, 0.0, 0.0)
            source.addFilter<VoicePlayer> { vp ->
                // With a player source active, it owns near delivery (positional, with
                // icons); the broadcast source reaches everyone else: members far away and
                // in other dimensions. Without a player source (older PV), the broadcast
                // reaches every eligible listener but the speaker.
                if (playerSource != null) {
                    isEligibleListener(vp) &&
                        (!isSameDimension(vp, speakerLevel) || !isWithinSpeakerRange(vp, speakerVoice, thresholdSq))
                } else {
                    isEligibleListener(vp) && vp.instance.uuid != speakerUuid
                }
            }
        }

        logger.debug("Group SVC->PV: created group sources for $speakerUuid (playerSource=${playerSource != null})")
        return GroupSources(speakerPlayer, speakerLevel, pvGroup.id, playerSource, broadcast)
    }

    private fun isSameDimension(vp: VoicePlayer, level: net.minecraft.server.level.ServerLevel): Boolean =
        mod.server?.playerList?.getPlayer(vp.instance.uuid)
            ?.let { runCatching { it.serverLevel() }.getOrNull() } === level

    /** Near enough the speaker that positional audio is audible; beyond it the broadcast
     *  source takes over (non-positional, full volume). */
    private fun isWithinSpeakerRange(vp: VoicePlayer, speakerVoice: VoicePlayer?, thresholdSq: Double): Boolean {
        if (speakerVoice == null) return true
        val memberPos = (vp.instance as? su.plo.slib.api.server.entity.player.McServerPlayer)?.getServerPosition()
            ?: return true
        val speakerPos = (speakerVoice.instance as? su.plo.slib.api.server.entity.player.McServerPlayer)?.getServerPosition()
            ?: return true
        return memberPos.distanceSquared(speakerPos) <= thresholdSq
    }

    private fun lazyRosterAdd(pvAdapter: PvAdapter, addon: GroupsAddon, pvGroup: PvGroup, groupName: String, speakerUuid: UUID, speakerPlayer: net.minecraft.server.level.ServerPlayer) {
        if (pvGroup.playersIds.contains(speakerUuid)) return
        val voicePlayer = pvAdapter.voiceServer.playerManager.getPlayerByInstance(speakerPlayer) ?: return
        pvGroup.addPlayer(voicePlayer)
        registerOnGroupsLine(addon, pvGroup, voicePlayer)
        svcRoster[speakerUuid] = groupName
        logger.debug("Group bridge: added SVC speaker $speakerUuid to PV group '${pvGroup.name}' roster")
    }

    // ------------------------------------------------------------------
    // PV group member speaks -> SVC group members
    // ------------------------------------------------------------------

    /** The PV group the player is currently in, or null. Resolved live from the groups addon. */
    fun pvGroupNameOf(playerUuid: UUID): String? =
        pvAddon()?.groupManager?.groupByPlayer?.get(playerUuid)?.name

    /**
     * Relay a PV group member's frame (Groups activation) to the SVC group members.
     * @return true when the speaker's PV group is bridged to an SVC group (regardless of
     * whether any SVC members are currently online).
     */
    fun relayPvGroupToSvc(speakerUuid: UUID, speakerPlayer: net.minecraft.server.level.ServerPlayer, pvGroupName: String, opusData: ByteArray): Boolean {
        val api = svcApi() ?: return false
        val svcGroup = api.groups?.firstOrNull { it.name.equals(pvGroupName, ignoreCase = true) } ?: return false
        val members = svcMembersOf(svcGroup, api)

        val speakerLevel = runCatching { speakerPlayer.serverLevel() }.getOrNull() ?: return true
        val apiLevel = api.fromServerLevel(speakerLevel)
        val speakerChannels = staticChannels.computeIfAbsent(speakerUuid) { ConcurrentHashMap() }

        if (shouldLogGroupFrame(speakerUuid, "pv-svc")) {
            logger.info("Group PV->SVC: relaying $speakerUuid to ${members.size} SVC members (group=${svcGroup.name})")
        }

        for (connection in members) {
            val listenerUuid = connection.player.uuid
            if (listenerUuid == speakerUuid) continue
            // Dual-mod listeners receive PV group audio natively through their own client -
            // the static channel would double it.
            if (mod.sessionManager.isDualMod(listenerUuid)) continue
            val channel = speakerChannels.computeIfAbsent(listenerUuid) {
                api.createStaticAudioChannel(UUID.randomUUID(), apiLevel, connection)
                    ?: throw IllegalStateException("failed to create static audio channel")
            }
            try {
                channel.send(opusData)
                BridgeMetrics.plasmoToSvcFrames.incrementAndGet()
            } catch (t: Throwable) {
                logger.debug("Group PV->SVC: send failed for $listenerUuid: ${t.message}")
                BridgeMetrics.droppedFrames.incrementAndGet()
            }
        }
        return true
    }

    /** Flush the speaker's static channels when their activation ends (keep them for the next burst). */
    fun onPvActivationEnd(speakerUuid: UUID) {
        staticChannels[speakerUuid]?.values?.forEach { runCatching { it.flush() } }
    }

    // ------------------------------------------------------------------
    // Roster sync (SVC group membership -> PV group roster + line player list)
    // ------------------------------------------------------------------

    /**
     * Add the SVC player to the matching PV group's roster AND register them on the groups
     * line's player list (setPlayerSet), which is what PV clients render in the overlay's
     * group list. Called from the SVC JoinGroup event.
     */
    fun onSvcJoinGroup(svcGroup: SvcGroup, connection: VoicechatConnection) {
        val addon = pvAddon() ?: return
        val pvGroup = addon.groupManager.groups.values.firstOrNull { it.name.equals(svcGroup.name, ignoreCase = true) }
        val playerUuid = connection.player.uuid

        if (pvGroup == null) {
            // No PV group with this name yet ??guide the SVC player on how PV players join.
            sendChat(
                connection.player.player,
                "PV players can join this group by creating a group with the same name: /groups create name: ${svcGroup.name}"
            )
            return
        }
        if (pvGroup.playersIds.contains(playerUuid)) return
        val mcPlayer = connection.player.player ?: return
        val voicePlayer = PvAdapter.instance?.voiceServer?.playerManager?.getPlayerByInstance(mcPlayer) ?: return
        pvGroup.addPlayer(voicePlayer)
        registerOnGroupsLine(addon, pvGroup, voicePlayer)
        svcRoster[playerUuid] = svcGroup.name
        logger.debug("Group bridge: added SVC player $playerUuid to PV group '${pvGroup.name}' roster")
    }

    fun onSvcCreateGroup(svcGroup: SvcGroup?, connection: VoicechatConnection?) {
        if (svcGroup == null) return
        val addon = pvAddon() ?: return
        if (addon.groupManager.groups.values.any { it.name.equals(svcGroup.name, ignoreCase = true) }) return
        val target = connection?.player?.player ?: return
        sendChat(
            target,
            "PV players can join this group by creating a group with the same name: /groups create name: ${svcGroup.name}"
        )
    }

    /**
     * Send a chat line through slib's McPlayer.sendMessage(McTextComponent). The method only
     * exists on the slib version bundled with modern PV (not on the 1.1.7 API this jar
     * compiles against) -> reflection, silent no-op when unavailable.
     */
    private fun sendChat(mcPlayer: Any?, text: String) {
        if (mcPlayer == null) return
        runCatching {
            val compClass = Class.forName("su.plo.slib.api.chat.component.McTextComponent")
            val component = compClass.getDeclaredMethod("literal", String::class.java).invoke(null, text)
            mcPlayer.javaClass.methods
                .firstOrNull { it.name == "sendMessage" && it.parameterCount == 1 && it.parameterTypes[0] == compClass }
                ?.invoke(mcPlayer, component)
        }
    }

    /** Remove the SVC player from the PV group roster they were added to (SVC LeaveGroup event or disconnect). */
    fun onSvcLeaveGroup(playerUuid: UUID) {
        val groupName = svcRoster.remove(playerUuid) ?: return
        val addon = pvAddon() ?: return
        val pvGroup = addon.groupManager.groups.values.firstOrNull { it.name.equals(groupName, ignoreCase = true) } ?: return
        if (!pvGroup.playersIds.contains(playerUuid)) return

        val line = addon.groupManager.sourceLine
        val voicePlayer = mod.server?.playerList?.getPlayer(playerUuid)?.let { mc ->
            PvAdapter.instance?.voiceServer?.playerManager?.getPlayerByInstance(mc)
        }
        pvGroup.removePlayer(playerUuid)
        // Clear the player's set on the groups line (mirrors the groups addon's leave path).
        runCatching { voicePlayer?.let { vp -> line?.playerSetManager?.setPlayerSet(vp, null) } }
        logger.debug("Group bridge: removed SVC player $playerUuid from PV group '$groupName' roster")
    }

    /**
     * Register the player on the groups line's player list via setPlayerSet ??the same call
     * the groups addon performs when a member joins; it triggers the
     * SourceLinePlayersListPacket / SourceLinePlayerAddPacket updates that PV clients use
     * to render the group member list in the overlay.
     */
    private fun registerOnGroupsLine(addon: GroupsAddon?, pvGroup: PvGroup, voicePlayer: VoicePlayer) {
        runCatching {
            val line = addon?.groupManager?.sourceLine
                ?: PvAdapter.instance?.voiceServer?.sourceLineManager?.getLineByName("groups")?.orElse(null)
            line?.playerSetManager?.setPlayerSet(voicePlayer, pvGroup.playerSet)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun onPvPlayerDisconnected(playerUuid: UUID) {
        removeGroupSources(playerUuid)
        staticChannels.remove(playerUuid)?.values?.forEach { runCatching { it.flush() } }
        groupSequenceNumbers.remove(playerUuid)
    }

    fun onSvcPlayerDisconnected(playerUuid: UUID) {
        onSvcLeaveGroup(playerUuid)
        removeGroupSources(playerUuid)
        staticChannels.values.forEach { listeners ->
            listeners.remove(playerUuid)?.let { runCatching { it.flush() } }
        }
    }

    private fun removeGroupSources(playerUuid: UUID) {
        val entry = svcGroupSources.remove(playerUuid) ?: return
        val line = pvAddon()?.let { groupsSourceLine(it) }
        entry.playerSource?.let { runCatching { line?.removeSource(it.id) } }
        runCatching { line?.removeSource(entry.broadcastSource.id) }
    }

    fun shutdown() {
        // Remove the sources from the groups line, otherwise PV keeps orphaned sources
        // after a shutdown/reload (same cleanup as removeGroupSources).
        val line = pvAddon()?.let { groupsSourceLine(it) }
        svcGroupSources.values.forEach { entry ->
            entry.playerSource?.let { runCatching { line?.removeSource(it.id) } }
            runCatching { line?.removeSource(entry.broadcastSource.id) }
        }
        svcGroupSources.clear()
        staticChannels.values.forEach { listeners -> listeners.values.forEach { runCatching { it.flush() } } }
        staticChannels.clear()
        svcRoster.clear()
        groupSequenceNumbers.clear()
    }

    /** Bridged groups (SVC group and PV group with the same name both exist). */
    fun bridgedGroupNames(): List<Triple<String, Int, Int>> {
        val addon = pvAddon() ?: return emptyList()
        val api = svcApi() ?: return emptyList()
        val groups = api.groups ?: return emptyList()
        val pvByName = addon.groupManager.groups.values
            .filter { it.onlinePlayers.isNotEmpty() }
            .associateBy { it.name.lowercase(Locale.ROOT) }
        return groups.mapNotNull { svc ->
            val pv = pvByName[svc.name.lowercase(Locale.ROOT)] ?: return@mapNotNull null
            Triple(svc.name, svcMembersOf(svc, api).size, pv.onlinePlayers.size)
        }
    }

    companion object {
        const val ADDON_ID = "pv-addon-groups"
    }
}

