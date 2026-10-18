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
 * no member lists are cached:
 *
 * - SVC group member speaks: SVC delivers to SVC members natively; this bridge
 *   delivers the encrypted frame to the matching PV group's online members via a
 *   per-speaker broadcast source on the groups addon's source line (non-positional,
 *   works across dimensions).
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

    /** SVC speaker -> broadcast source used to reach the matching PV group. */
    private val svcBroadcastSources = ConcurrentHashMap<UUID, ServerBroadcastSource>()

    /** PV speaker -> (SVC listener -> static channel used to reach that listener). */
    private val staticChannels = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, StaticAudioChannel>>()

    private fun pvAddon(): GroupsAddon? {
        if (!detected) return null
        if (!mod.bridgeConfig.groupsEnabled) return null
        return runCatching {
            val container = PvAdapter.instance?.voiceServer?.addonManager?.getAddon(ADDON_ID)?.orElse(null) ?: return null
            val addon = container.getInstance().orElse(null) as? GroupsAddon ?: return null
            // Accessing groupManager throws if the groups addon has not initialized yet —
            // the outer runCatching turns that into "feature not ready".
            addon.groupManager
            addon
        }.getOrNull()
    }

    fun isAvailable(): Boolean = pvAddon() != null

    /** The groups addon's own source line — group audio rides on it so clients get a single "Groups" volume entry. */
    private fun groupsSourceLine(addon: GroupsAddon): BaseServerSourceLine? =
        runCatching { addon.groupManager.sourceLine }.getOrNull()

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    private fun findPvGroup(addon: GroupsAddon, name: String): PvGroup? =
        addon.groupManager.groups.values
            .filter { it.name.equals(name, ignoreCase = true) }
            .firstOrNull { it.onlinePlayers.isNotEmpty() }

    private fun findSvcGroup(name: String): SvcGroup? =
        svcApi()?.groups?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** SVC connections whose native group matches [svcGroup] (SVC group members online in voice). */
    private fun svcMembersOf(svcGroup: SvcGroup, api: de.maxhenkel.voicechat.api.VoicechatServerApi): List<VoicechatConnection> =
        mod.sessionManager.getAllSessions()
            .asSequence()
            .filter { it.hasModType(ModType.SIMPLE_VOICE_CHAT) }
            .mapNotNull { api.getConnectionOf(it.playerUuid) }
            .filter { it.group?.id == svcGroup.id }
            .toList()

    private fun svcApi() = mod.audioRelay.svcAdapter?.voicechatApi

    // ------------------------------------------------------------------
    // SVC group member speaks -> PV group members
    // ------------------------------------------------------------------

    fun relaySvcGroupToPv(speakerUuid: UUID, speakerPlayer: net.minecraft.server.level.ServerPlayer, svcGroup: SvcGroup, opusData: ByteArray) {
        val addon = pvAddon() ?: return
        val line = groupsSourceLine(addon) ?: return
        val pvAdapter = PvAdapter.instance ?: return

        val pvGroup = addon.groupManager.groupByPlayer[speakerUuid]?.takeIf { it.name.equals(svcGroup.name, ignoreCase = true) }
            ?: findPvGroup(addon, svcGroup.name) ?: return
        val members = pvGroup.onlinePlayers.toList()
        if (members.isEmpty()) return

        val encrypted = try {
            pvAdapter.voiceServer.defaultEncryption.encrypt(opusData)
        } catch (e: Exception) {
            logger.debug("Group SVC->PV: failed to encrypt audio: ${e.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
            return
        }

        val speakerVoice = pvAdapter.voiceServer.playerManager.getPlayerByInstance(speakerPlayer)
        val source = svcBroadcastSources.computeIfAbsent(speakerUuid) {
            line.createBroadcastSource().also { source ->
                source.sender = speakerVoice
                source.addFilter<VoicePlayer> { it.instance.uuid != speakerUuid }
                logger.debug("Group SVC->PV: created broadcast source for $speakerUuid")
            }
        }

        source.players = members
        val seq = groupSequenceNumbers.merge(speakerUuid, 1L) { current, _ -> current + 1 } ?: 1L

        // Same send path the groups addon itself uses: a SourceAudioPacket delivered by the
        // broadcast source to its explicit player set (non-positional, cross-dimension).
        val sourcePacket = SourceAudioPacket(seq, source.state.toByte(), encrypted, source.id, 0)
        val speakerPacket = PlayerAudioPacket(seq, encrypted, pvGroup.id, 0, false)
        try {
            source.sendAudioPacket(sourcePacket, PlayerActivationInfo(speakerVoice, speakerPacket))
            BridgeMetrics.svcToPlasmoFrames.incrementAndGet()
        } catch (t: Throwable) {
            logger.debug("Group SVC->PV: send failed for $speakerUuid: ${t.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
        }
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
    // Lifecycle
    // ------------------------------------------------------------------

    fun onPvPlayerDisconnected(playerUuid: UUID) {
        svcBroadcastSources.remove(playerUuid)
        staticChannels.remove(playerUuid)?.values?.forEach { runCatching { it.flush() } }
        groupSequenceNumbers.remove(playerUuid)
    }

    fun onSvcPlayerDisconnected(playerUuid: UUID) {
        svcBroadcastSources.remove(playerUuid)
        staticChannels.values.forEach { listeners ->
            listeners.remove(playerUuid)?.let { runCatching { it.flush() } }
        }
    }

    fun shutdown() {
        svcBroadcastSources.clear()
        staticChannels.values.forEach { listeners -> listeners.values.forEach { runCatching { it.flush() } } }
        staticChannels.clear()
        groupSequenceNumbers.clear()
    }

    /** Bridged group names (SVC group and PV group with the same name both exist). */
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
