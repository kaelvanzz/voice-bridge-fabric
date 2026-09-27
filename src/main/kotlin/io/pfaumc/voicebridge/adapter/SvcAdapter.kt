package io.pfaumc.voicebridge.adapter

import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent
import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgeMod
import io.pfaumc.voicebridge.session.ModType
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

    // EntityAudioChannels for relaying PV player audio to SVC clients.
    // Key: PV player UUID (the "speaker"), Value: channel that SVC clients listen to.
    private val outboundChannels = ConcurrentHashMap<UUID, EntityAudioChannel>()

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
        registration.registerEvent(MicrophonePacketEvent::class.java, ::onMicrophonePacket)
        registration.registerEvent(PlayerConnectedEvent::class.java, ::onPlayerConnected)
        registration.registerEvent(PlayerDisconnectedEvent::class.java, ::onPlayerDisconnected)
    }

    // --- Event Handlers ---

    private fun onPlayerConnected(event: PlayerConnectedEvent) {
        val mod = plugin ?: return
        val connection = event.connection
        val playerUuid = connection.player.uuid
        val serverPlayer = connection.player.player as? ServerPlayer
        val playerName = serverPlayer?.gameProfile?.name ?: playerUuid.toString()

        // Register this player as an SVC user
        mod.sessionManager.register(playerUuid, playerName, ModType.SIMPLE_VOICE_CHAT)
        logger.info("SVC player connected: $playerName")

        // Register a fake UDP connection in PV so PV clients see a voice icon
        PvAdapter.instance?.registerBridgedConnection(playerUuid)
    }

    private fun onPlayerDisconnected(event: PlayerDisconnectedEvent) {
        val mod = plugin ?: return
        val playerUuid = event.playerUuid

        // Remove the fake PV connection before unregistering the session
        PvAdapter.instance?.removeBridgedConnection(playerUuid)

        // Remove only the SVC mod type; session is fully removed only when all mod types are gone
        mod.sessionManager.unregister(playerUuid, ModType.SIMPLE_VOICE_CHAT)

        // Close any outbound channels for this player
        outboundChannels.remove(playerUuid)?.let { channel ->
            channel.flush()
            logger.debug("Closed outbound channel for disconnected SVC player $playerUuid")
        }

        // Signal audio end on PV side for this player's outbound source
        PvAdapter.instance?.cleanupSource(playerUuid)
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

        // Relay to PV players
        mod.audioRelay.relaySvcToPv(
            senderUuid = senderUuid,
            senderPlayer = senderPlayer,
            opusData = opusData,
            sequenceNumber = 0, // SVC MicrophonePacket doesn't expose sequence to API
            distance = distance,
            whispering = whispering
        )
    }

    // --- Outbound: Send audio FROM a PV player TO SVC clients ---

    /**
     * Send audio from a PV player to nearby SVC clients using EntityAudioChannel.
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

        // Get existing channel or create a new one
        var channel = outboundChannels[senderUuid]
        if (channel == null) {
            val entity = api.fromEntity(senderPlayer)
            val channelId = UUID.nameUUIDFromBytes("voice-bridge-$senderUuid".toByteArray())
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
            outboundChannels[senderUuid] = newChannel
            channel = newChannel
            logger.info("PV→SVC: created EntityAudioChannel for $senderUuid")
        }

        channel.distance = distance
        channel.send(opusData)
        return true
    }

    /**
     * Clean up resources for a PV player who stopped talking.
     */
    fun flushChannel(senderUuid: UUID) {
        outboundChannels[senderUuid]?.flush()
    }

    /**
     * Remove channel for a player (e.g., on disconnect).
     */
    fun removeChannel(senderUuid: UUID) {
        outboundChannels.remove(senderUuid)?.flush()
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
        outboundChannels.values.forEach { it.flush() }
        outboundChannels.clear()
        logger.info("SVC adapter shut down")
    }

    companion object {
        var instance: SvcAdapter? = null
            private set
    }
}
