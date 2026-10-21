package io.pfaumc.voicebridge.adapter

import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgeMod
import io.pfaumc.voicebridge.session.ModType
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import su.plo.voice.api.addon.AddonInitializer
import su.plo.voice.api.addon.InjectPlasmoVoice
import su.plo.voice.api.addon.annotation.Addon
import su.plo.voice.api.encryption.EncryptionException
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.api.server.PlasmoVoiceServer
import su.plo.voice.api.server.audio.capture.ServerActivation
import su.plo.voice.api.server.audio.line.ServerSourceLine
import su.plo.voice.api.server.audio.source.ServerEntitySource
import su.plo.voice.api.server.event.audio.capture.PlayerServerActivationEndEvent
import su.plo.voice.api.server.event.audio.capture.PlayerServerActivationEvent
import su.plo.voice.api.server.event.connection.UdpClientConnectEvent
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent
import su.plo.voice.api.server.player.VoicePlayer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Adapter for Plasmo Voice.
 *
 * Uses PV's addon system:
 * 1. Annotated with @Addon for PV to recognize it
 * 2. Implements AddonInitializer for lifecycle callbacks
 * 3. Uses @InjectPlasmoVoice for DI of the PlasmoVoiceServer instance
 * 4. Loaded via PlasmoVoiceServer.getAddonsLoader().load(this) from the main mod initializer
 *
 * Audio interception:
 * - Registers a listener on the "proximity" ServerActivation to intercept PV player audio
 * - Creates ServerEntitySources to relay SVC player audio to PV clients
 */
@Addon(
    id = "voice-bridge",
    name = "Voice Bridge",
    version = "0.1.0",
    authors = ["VoiceBridge"]
)
class PvAdapter(private val plugin: VoiceBridgeMod) : AddonInitializer {

    private val logger: Logger = LoggerFactory.getLogger("VoiceBridge-PV")

    // Injected by PV's addon loading system via @InjectPlasmoVoice
    @InjectPlasmoVoice
    lateinit var voiceServer: PlasmoVoiceServer

    private var sourceLine: ServerSourceLine? = null
    private var proximityActivation: ServerActivation? = null

    // Re-paces bursty SVC frames into a steady 20ms cadence before sending to PV clients.
    private var repacer: FrameRepacer? = null

    // ServerEntitySources for relaying SVC player audio to PV clients.
    // Key: SVC player UUID (the "speaker").
    //
    // The source anchors to the speaker's entity at creation. A respawn replaces the
    // ServerPlayer instance and a cross-dimension teleport moves it to another level, so a
    // stale source broadcasts from the death spot / old dimension. Recreate the source
    // whenever the captured entity or level instance no longer matches.
    private class OutboundSource(
        val source: ServerEntitySource,
        val entity: ServerPlayer,
        val level: ServerLevel,
        /** The speaker's bridged PV group at source creation; rebuild when it changes. */
        val pvGroupId: UUID?
    )

    private val outboundSources = ConcurrentHashMap<UUID, OutboundSource>()

    // Track sequence numbers per source for outbound audio
    private val sequenceNumbers = ConcurrentHashMap<UUID, Long>()

    // Fake bridged UDP connections (SVC-only players): player UUID -> the fake connection's
    // own secret. Storing the secret lets us remove exactly the fake connection ??a dual-mod
    // player's real PV connection may have replaced PV's per-player index, so resolving the
    // secret by player id could target the wrong connection.
    private val bridgedConnections = ConcurrentHashMap<UUID, UUID>()

    // One-shot diagnostics for the PV?嚙磅VC chain (logged once, then suppressed)
    @Volatile private var loggedActivationMismatch = false
    @Volatile private var loggedCastFailure = false
    @Volatile private var loggedFirstFrame = false

    init {
        instance = this
        // Register this addon with PV's addon loader.
        // PV will discover the @Addon annotation, inject @InjectPlasmoVoice fields,
        // and call onAddonInitialize() ??on PV 2.1.x this happens during server start,
        // after PV's config (and therefore activations/source lines) are ready.
        try {
            PlasmoVoiceServer.getAddonsLoader().load(this)
            logger.info("Registered VoiceBridge as PV addon via AddonsLoader")
        } catch (e: Exception) {
            logger.warn("Failed to register with PV AddonsLoader: ${e.message}")
        }
    }

    override fun onAddonInitialize() {
        logger.info("PV addon initializing ??PlasmoVoiceServer injected")

        // Get the proximity source line for creating player sources
        sourceLine = voiceServer.sourceLineManager.getLineByName("proximity").orElse(null)
        if (sourceLine == null) {
            logger.warn("Could not find 'proximity' source line ??PV bridge may not work correctly")
        }

        // Resolve the proximity activation ID for filtering events
        proximityActivation = voiceServer.activationManager
            .getActivationByName("proximity")
            .orElse(null)
        if (proximityActivation == null) {
            logger.warn("Proximity activation not found ??PV?嚙磅VC bridge may not work")
        }

        val cfg = plugin.bridgeConfig
        if (cfg.repacing) {
            val lead = cfg.repacingJitterFrames.coerceAtLeast(1)
            repacer = FrameRepacer(
                frameIntervalMs = FRAME_INTERVAL_MS,
                leadFrames = lead,
                maxFrames = lead + MAX_EXTRA_FRAMES,
                sink = ::emitFrame
            ).also { it.start() }
            logger.info("SVC->PV frame re-pacing enabled (lead=$lead frames, ${FRAME_INTERVAL_MS}ms cadence)")
        }

        logger.info("PV adapter initialized, listening for activation events")
    }

    override fun onAddonShutdown() {
        shutdown()
    }

    // --- Event Handlers ---

    @EventSubscribe
    fun onPlayerConnected(event: UdpClientConnectEvent) {
        val player = event.connection.player
        val playerUuid = player.instance.uuid

        // Our own fake bridged connection fires this event too ??ignore it entirely.
        // (Registering the fake connection must NOT tear it down or mark the player as PV!)
        if (event.connection is BridgedUdpConnection) return

        // A REAL PV connection arrived for a player we created a fake bridged connection for
        // (dual-mod player whose SVC voice connected first) ??tear the fake down and continue
        // with normal registration.
        if (bridgedConnections.containsKey(playerUuid)) {
            removeFakeConnection(playerUuid)
            logger.info("Replaced fake bridged connection with real PV connection for $playerUuid")
        }

        val playerName = player.instance.name

        // Register PV mod type ??if already registered as SVC, this adds PV as second mod type
        plugin.sessionManager.register(playerUuid, playerName, ModType.PLASMO_VOICE)
        logger.info("PV player connected: $playerName")

        // Mark this player as connected in SVC so SVC clients see a voice icon
        SvcAdapter.instance?.setExternalPlayerConnected(playerUuid, true)
    }

    @EventSubscribe
    fun onPlayerDisconnected(event: UdpClientDisconnectedEvent) {
        val player = event.connection.player
        val playerUuid = player.instance.uuid

        // If this is our own fake bridged connection being removed, just clean up the map
        if (bridgedConnections.remove(playerUuid) != null) return

        // Mark this player as disconnected in SVC
        SvcAdapter.instance?.setExternalPlayerConnected(playerUuid, false)

        // Remove only the PV mod type; session is fully removed only when all mod types are gone
        plugin.sessionManager.unregister(playerUuid, ModType.PLASMO_VOICE)

        // Clean up outbound sources
        outboundSources.remove(playerUuid)?.let { runCatching { it.source.remove() } }
        sequenceNumbers.remove(playerUuid)
        repacer?.remove(playerUuid)

        // Clean up outbound SVC channels for this player
        SvcAdapter.instance?.removeChannel(playerUuid)

        // Group bridge cleanup
        plugin.groupBridge.onPvPlayerDisconnected(playerUuid)
    }

    // --- Audio Reception from PV Players ---
    // Uses @EventSubscribe on PlayerServerActivationEvent instead of activation.onPlayerActivation
    // because PV's default ProximityServerActivationHelper returns HANDLED, which blocks
    // any subsequently-registered activation listeners from being called.
    // The event bus fires BEFORE the listener loop, so we're guaranteed to receive audio.

    @EventSubscribe
    fun onPlayerActivation(event: PlayerServerActivationEvent) {
        val player = event.player
        val packet = event.packet

        // Proximity audio: relay as usual. Non-proximity activations (e.g. the groups
        // addon's) are handled by the group bridge below when they belong to a bridged
        // group; only warn if the bridge cannot be responsible for the activation.
        if (event.activation != proximityActivation) {
            val decrypted = try {
                voiceServer.defaultEncryption.decrypt(packet.data)
            } catch (e: EncryptionException) {
                logger.debug("Failed to decrypt PV audio from ${player.instance.name}: ${e.message}")
                BridgeMetrics.droppedFrames.incrementAndGet()
                return
            }
            val handledByGroups = plugin.groupBridge.relayPvGroupToSvc(
                speakerUuid = player.instance.uuid,
                speakerPlayer = plugin.server?.playerList?.getPlayer(player.instance.uuid) ?: return,
                pvGroupName = plugin.groupBridge.pvGroupNameOf(player.instance.uuid) ?: return,
                opusData = decrypted
            )
            if (!handledByGroups && !loggedActivationMismatch) {
                loggedActivationMismatch = true
                logger.warn(
                    "PV activation event received but does not match our 'proximity' activation " +
                        "(event=${event.activation?.javaClass?.simpleName}) ??PV?嚙磅VC relay will not work"
                )
            }
            return
        }

        val playerUuid = player.instance.uuid
        val distance = packet.distance
        val sequenceNumber = packet.sequenceNumber

        // PV audio data is encrypted end-to-end (AES/CBC/PKCS5Padding).
        // We must decrypt it before relaying raw Opus to SVC.
        val opusData = try {
            voiceServer.defaultEncryption.decrypt(packet.data)
        } catch (e: EncryptionException) {
            logger.warn("Failed to decrypt PV audio from ${player.instance.name}: ${e.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
            return
        }

        // Touch session
        plugin.sessionManager.getSession(playerUuid)?.touch()

        // Get the server player.
        // PV's VoicePlayer.instance is a slib wrapper (ModServerPlayer), not the raw
        // ServerPlayer ??look the player up by UUID from the server instead of casting.
        val senderPlayer = plugin.server?.playerList?.getPlayer(playerUuid) ?: run {
            if (!loggedCastFailure) {
                loggedCastFailure = true
                logger.warn("Could not find ServerPlayer for PV player ${player.instance.name} ??PV?嚙磅VC relay will not work")
            }
            return
        }

        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            logger.info("PV?嚙磅VC: received first audio frame from ${player.instance.name} (dist=$distance)")
        }

        // Relay to SVC players via AudioRelay
        plugin.audioRelay.relayPvToSvc(
            senderUuid = playerUuid,
            senderPlayer = senderPlayer,
            opusData = opusData,
            sequenceNumber = sequenceNumber,
            distance = distance
        )
    }

    @EventSubscribe
    fun onPlayerActivationEnd(event: PlayerServerActivationEndEvent) {
        val playerUuid = event.player.instance.uuid

        // Flush the group bridge's static channels for this speaker on any activation end
        plugin.groupBridge.onPvActivationEnd(playerUuid)

        if (event.activation != proximityActivation) return

        // Notify SVC adapter to flush the channel for this player
        SvcAdapter.instance?.flushChannel(playerUuid)
    }

    // --- Outbound: Send audio FROM an SVC player TO PV clients ---

    /**
     * Send audio from an SVC player to nearby PV clients using ServerEntitySource.
     *
     * Uses entity sources instead of player sources because SVC-only players
     * are not connected to PV's UDP server and cannot use ServerPlayerSource.
     *
     * @return true if audio was sent successfully
     */
    fun sendAudioFromExternalPlayer(
        senderUuid: UUID,
        senderPlayer: ServerPlayer,
        opusData: ByteArray,
        sequenceNumber: Long,
        distance: Short,
        speakerPvGroupId: UUID? = null
    ): Boolean {
        val line = sourceLine ?: return false
        val currentLevel = senderPlayer.serverLevel()

        var entry = outboundSources[senderUuid]
        if (entry != null && (entry.entity !== senderPlayer || entry.level !== currentLevel || entry.pvGroupId != speakerPvGroupId)) {
            runCatching { entry.source.remove() }
            outboundSources.remove(senderUuid, entry)
            logger.debug("SVC?嚙瞑V: stale entity source for $senderUuid (entity/level changed), recreating")
            entry = null
        }

        if (entry == null) {
            val mcEntity = voiceServer.minecraftServer.getPlayerByInstance(senderPlayer)
            val created = line.createEntitySource(mcEntity, false).apply {
                isIconVisible = true
                // Exclude dual-mod players ??they already hear SVC audio natively
                addFilter<VoicePlayer> { player -> !plugin.sessionManager.isDualMod(player.instance.uuid) }
            }
            entry = OutboundSource(created, senderPlayer, currentLevel, speakerPvGroupId)
            outboundSources[senderUuid] = entry
        }

        val pacer = repacer
        if (pacer != null) {
            pacer.enqueue(senderUuid, opusData, distance)
        } else {
            emitFrame(senderUuid, opusData, distance)
        }
        return true
    }

    /**
     * Encrypt and send a single Opus frame to PV clients for the given speaker.
     *
     * The sequence number is assigned here (monotonic +1 per source) rather than carried from the
     * caller, so it stays contiguous with frames actually sent ??PV's client-side packet-loss
     * compensation requires contiguous sequence numbers. When re-pacing is enabled this runs on the
     * pacer thread; otherwise it runs inline on the caller's thread.
     */
    private fun emitFrame(senderUuid: UUID, opusData: ByteArray, distance: Short) {
        val source = outboundSources[senderUuid]?.source ?: return

        val seq = sequenceNumbers.merge(senderUuid, 1L) { current, _ -> current + 1 } ?: 1L

        // PV uses end-to-end encryption (AES/CBC/PKCS5Padding).
        // Audio from SVC is raw Opus ??we must encrypt it before sending
        // so PV clients can decrypt and decode it.
        val encryptedData = try {
            voiceServer.defaultEncryption.encrypt(opusData)
        } catch (e: EncryptionException) {
            logger.debug("Failed to encrypt audio for PV: ${e.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
            return
        }

        // Never let a PV-side exception travel back onto the caller's thread.
        try {
            source.sendAudioFrame(encryptedData, seq, distance)
        } catch (t: Throwable) {
            logger.debug("SVC?嚙瞑V: send failed for $senderUuid: ${t.message}")
            BridgeMetrics.droppedFrames.incrementAndGet()
        }
    }

    /**
     * Signal end of audio stream for a player.
     */
    fun sendAudioEnd(senderUuid: UUID, distance: Short) {
        val seq = sequenceNumbers[senderUuid] ?: return
        outboundSources[senderUuid]?.let { runCatching { it.source.sendAudioEnd(seq, distance) } }
    }

    /**
     * Clean up the outbound PV source for a player (e.g., when an SVC player disconnects).
     * Sends audio end signal and removes the source.
     */
    fun cleanupSource(senderUuid: UUID) {
        repacer?.remove(senderUuid)
        val seq = sequenceNumbers.remove(senderUuid) ?: return
        outboundSources.remove(senderUuid)?.let { entry ->
            runCatching {
                entry.source.sendAudioEnd(seq, 0)
                entry.source.remove()
            }
            logger.debug("Cleaned up PV source for player $senderUuid")
        }
    }

    /**
     * Register a fake UDP connection for an SVC-only player so PV clients see a voice icon.
     */
    fun registerBridgedConnection(playerUuid: UUID) {
        if (!::voiceServer.isInitialized) return
        if (bridgedConnections.containsKey(playerUuid)) return

        val serverPlayer = plugin.server?.playerList?.getPlayer(playerUuid) ?: return
        val voicePlayer = voiceServer.playerManager.getPlayerByInstance(serverPlayer)

        val connection = BridgedUdpConnection(voicePlayer)
        bridgedConnections[playerUuid] = connection.secret
        voiceServer.udpConnectionManager.addConnection(connection)

        // SVC players never do the PV client handshake, so PV marks them voice-disabled and
        // PV clients hide their mic icons. Flip the flag (the setter lives on the impl class,
        // not the API interface ??reflection) and re-broadcast the player info below so PV
        // clients render them as voice-enabled.
        runCatching {
            voicePlayer.javaClass.methods
                .firstOrNull { it.name == "setVoiceDisabled" }
                ?.invoke(voicePlayer, false)
        }

        // Broadcast player info to all PV clients so they see a voice icon.
        // addConnection() alone only registers internally; the PlayerInfoUpdatePacket
        // must be sent explicitly for clients to update their player list.
        voiceServer.tcpPacketManager.broadcastPlayerInfoUpdate(voicePlayer)
        logger.debug("Registered bridged PV connection for SVC player $playerUuid")
    }

    /**
     * Remove a fake UDP connection for an SVC player who disconnected.
     */
    fun removeBridgedConnection(playerUuid: UUID) {
        if (!::voiceServer.isInitialized) return
        if (!bridgedConnections.containsKey(playerUuid)) return

        removeFakeConnection(playerUuid)
        // bridgedConnections entry is cleaned up in onPlayerDisconnected when the event fires
        logger.debug("Removed bridged PV connection for SVC player $playerUuid")
    }

    private fun removeFakeConnection(playerUuid: UUID) {
        val secret = bridgedConnections.remove(playerUuid) ?: return
        runCatching { voiceServer.udpConnectionManager.removeConnection(secret) }
    }

    fun shutdown() {
        repacer?.shutdown()
        repacer = null

        // Clean up any remaining bridged connections
        for (secret in bridgedConnections.values) {
            runCatching { voiceServer.udpConnectionManager.removeConnection(secret) }
        }
        bridgedConnections.clear()

        outboundSources.values.forEach { runCatching { it.source.remove() } }
        outboundSources.clear()
        sequenceNumbers.clear()
        logger.info("PV adapter shut down")
    }

    companion object {
        var instance: PvAdapter? = null
            private set

        // Both mods use 20ms Opus frames (960 samples @ 48kHz).
        private const val FRAME_INTERVAL_MS = 20L
        // Headroom above the lead before the pacer drops the oldest frame (bounds latency under drift).
        private const val MAX_EXTRA_FRAMES = 10
    }
}


