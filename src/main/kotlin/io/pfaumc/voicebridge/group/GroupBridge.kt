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
 * (case-insensitive). Matching is resolved live at event time on both sides —
 * no member lists are cached.
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
 *   (non-positional, cross-dimension, no positional icon — same as the groups
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
        val playerSource: ServerPlayerSource?,
        val broadcastSource: ServerBroadcastSource
    )

    private val svcGroupSources = ConcurrentHashMap<UUID, GroupSources>()

    private val staticChannels = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, StaticAudioChannel>>()

    /** SVC players currently mirrored onto a PV group roster (uuid -> bridged group name). */
    private val svcRoster = ConcurrentHashMap<UUID, String>()

    private fun pvAddon(): GroupsAddon? {
        if (!detected) return null
        if (!mod.bridgeConfig.groupsEnabled) return null
        return runCatching {
            val container = PvAdapter.instance?.voiceServer?.addonManager?.getAddon(ADDON_ID)?.orElse(null) ?: return null
            val addon = container.getInstance().orElse(null) as? GroupsAddon ?: return null
            // Accessing groupManager throws while the groups addon has not initialized yet —
            // the outer runCatching turns that into "feature not ready".
            addon.groupManager
            addon
        }.getOrNull()
    }

    fun isAvailable(): Boolean = pvAddon() != null

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
            ?.takeIf { it.entity === speakerPlayer && it.level === speakerLevel }
        if (entry == null) {
            entry = createGroupSources(line, pvAdapter, speakerUuid, speakerPlayer, speakerLevel)
            svcGroupSources[speakerUuid] = entry
        }

        val seq = groupSequenceNumbers.merge(speakerUuid, 1L) { current, _ -> current + 1 } ?: 1L

        // Same-dimension members: player source. PlayerSourceInfo is indexed per player on
        // the client, which drives the name-tag mic icon and the overlay attribution.
        entry.playerSource?.let { ps ->
            try {
                ps.sendAudioFrame(encrypted, seq, Short.MAX_VALUE)
                BridgeMetrics.svcToPlasmoFrames.incrementAndGet()
            } catch (t: Throwable) {
                logger.debug("Group SVC->PV: player-source send failed for $speakerUuid: ${t.message}")
                BridgeMetrics.droppedFrames.incrementAndGet()
            }
        }

        // Cross-dimension members: broadcast source (non-positional, no name-tag icon —
        // matches the groups addon's own cross-dimension delivery).
        val others = members.filter { memberLevel(it) !== speakerLevel }
        if (others.isNotEmpty()) {
            entry.broadcastSource.players = others
            val sourcePacket = SourceAudioPacket(seq, entry.broadcastSource.state.toByte(), encrypted, entry.broadcastSource.id, 0)
            val speakerVoice = pvAdapter.voiceServer.playerManager.getPlayerByInstance(speakerPlayer)
            val speakerPacket = PlayerAudioPacket(seq, encrypted, pvGroup.id, 0, false)
            try {
                entry.broadcastSource.sendAudioPacket(sourcePacket, PlayerActivationInfo(speakerVoice, speakerPacket))
                BridgeMetrics.svcToPlasmoFrames.incrementAndGet()
            } catch (t: Throwable) {
                logger.debug("Group SVC->PV: broadcast send failed for $speakerUuid: ${t.message}")
                BridgeMetrics.droppedFrames.incrementAndGet()
            }
        }
    }

    private fun findPvGroupForSpeaker(addon: GroupsAddon, speakerUuid: UUID, name: String): PvGroup? =
        addon.groupManager.groupByPlayer[speakerUuid]
            ?.takeIf { it.name.equals(name, ignoreCase = true) }
            ?: findPvGroup(addon, name)

    private fun createGroupSources(
        line: BaseServerSourceLine,
        pvAdapter: PvAdapter,
        speakerUuid: UUID,
        speakerPlayer: net.minecraft.server.level.ServerPlayer,
        speakerLevel: net.minecraft.server.level.ServerLevel
    ): GroupSources {
        val speakerVoice = pvAdapter.voiceServer.playerManager.getPlayerByInstance(speakerPlayer)

        // createPlayerSource exists on the runtime line class but not on the 2.1.8 API
        // interface this jar compiles against -> reflection, graceful null on older PV.
        val playerSource = runCatching {
            line.javaClass.methods.firstOrNull { it.name == "createPlayerSource" }
                ?.invoke(line, speakerVoice, false, null, null) as? ServerPlayerSource
        }.getOrNull()?.apply {
            isIconVisible = true
            addFilter<VoicePlayer> { vp -> isSameDimension(vp, speakerLevel) }
        }

        val broadcast = line.createBroadcastSource().also { source ->
            source.sender = speakerVoice
            source.isIconVisible = true
            source.addFilter<VoicePlayer> { vp ->
                // With a player source active it owns same-dimension delivery; the broadcast
                // source only reaches members in other dimensions. Without one (older PV),
                // the broadcast reaches everyone but the speaker.
                if (playerSource != null) !isSameDimension(vp, speakerLevel) else vp.instance.uuid != speakerUuid
            }
        }

        logger.debug("Group SVC->PV: created group sources for $speakerUuid (playerSource=${playerSource != null})")
        return GroupSources(speakerPlayer, speakerLevel, playerSource, broadcast)
    }

    private fun isSameDimension(vp: VoicePlayer, level: net.minecraft.server.level.ServerLevel): Boolean =
        mod.server?.playerList?.getPlayer(vp.instance.uuid)
            ?.let { runCatching { it.serverLevel() }.getOrNull() } === level

    private fun memberLevel(vp: VoicePlayer): net.minecraft.server.level.ServerLevel? =
        mod.server?.playerList?.getPlayer(vp.instance.uuid)?.let { runCatching { it.serverLevel() }.getOrNull() }

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

        for (connection in members) {
            val listenerUuid = connection.player.uuid
            if (listenerUuid == speakerUuid) continue
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
        val pvGroup = addon.groupManager.groups.values.firstOrNull { it.name.equals(svcGroup.name, ignoreCase = true) } ?: return
        val playerUuid = connection.player.uuid
        if (pvGroup.playersIds.contains(playerUuid)) return
        val mcPlayer = connection.player.player ?: return
        val voicePlayer = PvAdapter.instance?.voiceServer?.playerManager?.getPlayerByInstance(mcPlayer) ?: return
        pvGroup.addPlayer(voicePlayer)
        registerOnGroupsLine(addon, pvGroup, voicePlayer)
        svcRoster[playerUuid] = svcGroup.name
        logger.debug("Group bridge: added SVC player $playerUuid to PV group '${pvGroup.name}' roster")
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
     * Register the player on the groups line's player list via setPlayerSet — the same call
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
        private val groupSequenceNumbers = ConcurrentHashMap<UUID, Long>()
    }
}
