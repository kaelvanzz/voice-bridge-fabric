package io.pfaumc.voicebridge.adapter

import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.JoinGroupEvent
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent
import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgeMod
import io.pfaumc.voicebridge.compat.Compat
import io.pfaumc.voicebridge.session.ModType
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Adapter for Simple Voice Chat.
 *
 * Implements VoicechatPlugin to hook into SVC's server-side API.
 * - Listens for MicrophonePacketEvents from SVC players and relays to PV players via AudioRelay.
 * - Creates EntityAudioChannels to relay audio FROM PV players TO SVC players.
 *
 * Registration: This class is registered as a VoicechatPlugin via the "voicechat" entrypoint in
 * fabric.mod.json. Simple Voice Chat instantiates it when the voice chat server starts.
 */
class SvcAdapter : VoicechatPlugin {

    private val logger: Logger = LoggerFactory.getLogger("VoiceBridge-SVC")

    private var serverApi: VoicechatServerApi? = null

    /** Public accessor for other components (GroupBridge) that need the SVC server API. */
    val voicechatApi: VoicechatServerApi? get() = serverApi

    // EntityAudioChannels for relaying PV player audio to SVC clients.
    // Key: PV player UUID (the "speaker").
    //
    // SVC captures the speaker's entity at channel creation and computes the audience from
    // that object's live level+position on every frame. A respawn replaces the ServerPlayer
    // instance, and a cross-dimension teleport can leave the captured entity momentarily
    // outside any level (null level accessor on 1.21.9+), so a stale anchor either
    // broadcasts from the death spot / old dimension or throws inside SVC's broadcast on
    // the PV UDP thread — killing PV->SVC until the PV player rejoins. Recreate the
    // channel whenever the captured entity or level instance no longer matches.
    private class OutboundChannel(
        val channel: EntityAudioChannel,
        val entity: ServerPlayer,
        val level: ServerLevel
    )

    private val outboundChannels = ConcurrentHashMap<UUID, OutboundChannel>()

    private val plugin: VoiceBridgeMod?
        get() = VoiceBridgeMod.instance?.takeIf { VoiceBridgeMod.isReady() }

    init {
        instance = this
    }

    override fun getPluginId(): String = "voice-bridge"

    override fun initialize(api: VoicechatApi) {
        if (api is VoicechatServerApi) {
            this.serverApi = api
            logger.info("SVC server API initialized")
        }
    }

    override fun registerEvents(registration: EventRegistration) {
        // 3-arg registerEvent on purpose: the 2-arg overload is a default method added in
        // API 2.6.x and does not exist on SVC 2.5.x runtimes (e.g. MC 1.21.6) — calling it
        // directly would throw NoSuchMethodError there. Priority 0 matches the 2.6.x default.
        registration.registerEvent(MicrophonePacketEvent::class.java, ::onMicrophonePacket, 0)
        registration.registerEvent(PlayerConnectedEvent::class.java, ::onPlayerConnected, 0)
        registration.registerEvent(PlayerDisconnectedEvent::class.java, ::onPlayerDisconnected, 0)
        registration.registerEvent(JoinGroupEvent::class.java, ::onJoinGroup, 0)
        registration.registerEvent(LeaveGroupEvent::class.java, ::onLeaveGroup, 0)
    }

    // --- Event Handlers ---

    private fun onPlayerConnected(event: PlayerConnectedEvent) {
        val mod = plugin ?: return
        val connection = event.connection
        val playerUuid = connection.player.uuid
        val serverPlayer = connection.player.player as? ServerPlayer
        val playerName = serverPlayer?.let { Compat.profileName(it.gameProfile) } ?: playerUuid.toString()

        // Register this player as an SVC user
        mod.sessionManager.register(playerUuid, playerName, ModType.SIMPLE_VOICE_CHAT)
        logger.info("SVC player connected: $playerName")

        // Register a fake UDP connection in PV so PV clients see a voice icon — but only
        // for SVC-only players. A player whose PV side already connected must not get a
        // fake connection: it would shadow their real one and hide their dual-mod status.
        val session = mod.sessionManager.getSession(playerUuid)
        if (session == null || !session.hasModType(ModType.PLASMO_VOICE)) {
            PvAdapter.instance?.registerBridgedConnection(playerUuid)
        }
    }

    private fun onPlayerDisconnected(event: PlayerDisconnectedEvent) {
        val mod = plugin ?: return
        val playerUuid = event.playerUuid

        // Remove the fake PV connection before unregistering the session
        PvAdapter.instance?.removeBridgedConnection(playerUuid)

        // Remove only the SVC mod type; session is fully removed only when all mod types are gone
        mod.sessionManager.unregister(playerUuid, ModType.SIMPLE_VOICE_CHAT)

        // Close any outbound channels for this player
        outboundChannels.remove(playerUuid)?.let { entry ->
            runCatching { entry.channel.flush() }
            logger.debug("Closed outbound channel for disconnected SVC player $playerUuid")
        }

        // Signal audio end on PV side for this player's outbound source
        PvAdapter.instance?.cleanupSource(playerUuid)

        // Group bridge cleanup
        mod.groupBridge.onSvcPlayerDisconnected(playerUuid)
    }

    private fun onJoinGroup(event: JoinGroupEvent) {
        val mod = plugin ?: return
        val group = event.group ?: return
        val connection = event.connection ?: return
        mod.groupBridge.onSvcJoinGroup(group, connection)
    }

    private fun onLeaveGroup(event: LeaveGroupEvent) {
        val mod = plugin ?: return
        mod.groupBridge.onSvcLeaveGroup(event.connection?.player?.uuid ?: return)
    }

    /**
     * Called when an SVC player sends a microphone packet.
     * Relay this audio to PV players via the AudioRelay.
     */
    private fun onMicrophonePacket(event: MicrophonePacketEvent) {
        val mod = plugin ?: return
        val senderConnection = event.senderConnection ?: return
        val senderUuid = senderConnection.player.uuid
        val packet = event.packet
        val opusData = packet.opusEncodedData
        if (opusData.isEmpty()) return
        val whispering = packet.isWhispering

        // Touch session to keep it alive
        mod.sessionManager.getSession(senderUuid)?.touch()

        // Get the server player for position info
        val senderPlayer = senderConnection.player.player as? ServerPlayer ?: return

        // Get the configured distance
        val api = serverApi ?: return
        val distance = api.voiceChatDistance.toFloat()

        // Relay to PV players (proximity)
        mod.audioRelay.relaySvcToPv(
            senderUuid = senderUuid,
            senderPlayer = senderPlayer,
            opusData = opusData,
            sequenceNumber = 0, // SVC MicrophonePacket doesn't expose sequence to API
            distance = distance,
            whispering = whispering
        )

        // SVC group audio: relay to the matching PV group channel if one is bridged
        val svcGroup = senderConnection.group ?: return
        mod.groupBridge.relaySvcGroupToPv(senderUuid, senderPlayer, svcGroup, opusData)
    }

    // --- Outbound: Send audio FROM a PV player TO SVC clients ---

    /**
     * Send audio from a PV player to nearby SVC clients using EntityAudioChannel.
     *
     * The channel is recreated whenever the speaker's entity or level instance changes
     * (respawn, cross-dimension teleport) — see [OutboundChannel] for why.
     *
     * @return true if audio was sent successfully
     */
    fun sendAudioFromExternalPlayer(
        senderUuid: UUID,
        senderPlayer: ServerPlayer,
        opusData: ByteArray,
        sequenceNumber: Long,
        distance: Float
    ): Boolean {
        val mod = plugin ?: return false
        val api = serverApi ?: return false
        val currentLevel = senderPlayer.serverLevel()

        var entry = outboundChannels[senderUuid]
        if (entry != null && (entry.entity !== senderPlayer || entry.level !== currentLevel || entry.channel.isClosed)) {
            runCatching { entry.channel.flush() }
            outboundChannels.remove(senderUuid, entry)
            logger.debug("PV→SVC: stale EntityAudioChannel for $senderUuid (entity/level changed), recreating")
            entry = null
        }

        if (entry == null) {
            val entity = api.fromEntity(senderPlayer)
            // Fresh random id per recreation — never collides with a channel SVC still has
            // registered from a previous entity/level generation of the same player, and
            // needs no per-player state.
            val channelId = UUID.randomUUID()
            val newChannel = api.createEntityAudioChannel(channelId, entity)
            if (newChannel == null) {
                logger.warn("Failed to create EntityAudioChannel for PV player $senderUuid")
                BridgeMetrics.droppedFrames.incrementAndGet()
                return false
            }
            newChannel.distance = distance
            // Set filter once at creation — only send to SVC players who are NOT dual-mod
            newChannel.setFilter { serverPlayer ->
                val session = mod.sessionManager.getSession(serverPlayer.uuid)
                session != null && session.hasModType(ModType.SIMPLE_VOICE_CHAT) && !session.isDualMod()
            }
            entry = OutboundChannel(newChannel, senderPlayer, currentLevel)
            outboundChannels[senderUuid] = entry
            logger.info("PV→SVC: created EntityAudioChannel for $senderUuid")
        }

        // Never let an SVC-side exception travel back onto PV's UDP thread.
        return try {
            entry.channel.distance = distance
            entry.channel.send(opusData)
            true
        } catch (t: Throwable) {
            logger.warn("PV→SVC: send failed for $senderUuid: ${t.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
            false
        }
    }

    /**
     * Clean up resources for a PV player who stopped talking.
     */
    fun flushChannel(senderUuid: UUID) {
        outboundChannels[senderUuid]?.let { runCatching { it.channel.flush() } }
    }

    /**
     * Remove channel for a player (e.g., on disconnect).
     */
    fun removeChannel(senderUuid: UUID) {
        outboundChannels.remove(senderUuid)?.let { runCatching { it.channel.flush() } }
    }

    /**
     * Mark a non-SVC player as connected/disconnected in SVC's player state.
     * This updates the voice icon for SVC clients.
     * Only works for players without the SVC mod installed (i.e., PV-only players).
     */
    fun setExternalPlayerConnected(playerUuid: UUID, connected: Boolean) {
        serverApi?.getConnectionOf(playerUuid)?.isConnected = connected
    }

    fun shutdown() {
        outboundChannels.values.forEach { runCatching { it.channel.flush() } }
        outboundChannels.clear()
        logger.info("SVC adapter shut down")
    }

    companion object {
        var instance: SvcAdapter? = null
            private set
    }
}
