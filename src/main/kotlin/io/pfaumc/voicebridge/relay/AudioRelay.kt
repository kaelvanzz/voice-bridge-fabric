package io.pfaumc.voicebridge.relay

import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.adapter.PvAdapter
import io.pfaumc.voicebridge.adapter.SvcAdapter
import io.pfaumc.voicebridge.config.BridgeConfig
import io.pfaumc.voicebridge.session.SessionManager
import io.pfaumc.voicebridge.spatial.SpatialMapper
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Central audio relay that routes voice frames between the two mod adapters.
 *
 * Audio flow:
 * - SVC player speaks ??SvcAdapter receives MicrophonePacketEvent ??calls relaySvcToPv()
 * - PV player speaks ??PvAdapter receives activation event ??calls relayPvToSvc()
 */
class AudioRelay(
    private val sessionManager: SessionManager,
    private val spatialMapper: SpatialMapper,
    private val config: BridgeConfig,
    private val debugProvider: () -> Boolean
) {
    private val logger: Logger = LoggerFactory.getLogger("VoiceBridge")

    val svcAdapter: SvcAdapter?
        get() = SvcAdapter.instance
    val pvAdapter: PvAdapter?
        get() = PvAdapter.instance

    /**
     * Relay audio from a Simple Voice Chat player to all nearby Plasmo Voice players.
     *
     * @param senderUuid UUID of the SVC player speaking
     * @param senderPlayer the server player entity of the sender
     * @param opusData Opus-encoded audio frame
     * @param sequenceNumber packet sequence number
     * @param distance hearing distance (SVC float)
     * @param whispering whether the sender is whispering
     */
    fun relaySvcToPv(
        senderUuid: UUID,
        senderPlayer: ServerPlayer,
        opusData: ByteArray,
        sequenceNumber: Long,
        distance: Float,
        whispering: Boolean
    ) {
        val pv = pvAdapter ?: return

        // Skip relay for dual-mod players ??PV clients already hear them via PV natively
        if (sessionManager.isDualMod(senderUuid)) return

        val effectiveDistance = if (whispering) {
            spatialMapper.whisperDistance(distance.toDouble())
        } else {
            distance.toDouble()
        }
        val worldName = senderPlayer.level().dimension().identifier().path
        val pvDistance = spatialMapper.svcToPvDistance(effectiveDistance.toFloat(), worldName)

        // Let the PV adapter handle sending to nearby PV players
        val sent = pv.sendAudioFromExternalPlayer(
            senderUuid,
            senderPlayer,
            opusData,
            sequenceNumber,
            pvDistance
        )

        if (sent) {
            BridgeMetrics.svcToPlasmoFrames.incrementAndGet()
        }

        if (debugProvider()) {
            logger.debug("SVC->PV: ${senderPlayer.gameProfile.name} seq=$sequenceNumber dist=$pvDistance whisper=$whispering")
        }
    }

    /**
     * Relay audio from a Plasmo Voice player to all nearby Simple Voice Chat players.
     *
     * @param senderUuid UUID of the PV player speaking
     * @param senderPlayer the server player entity of the sender
     * @param opusData Opus-encoded audio frame
     * @param sequenceNumber packet sequence number
     * @param distance hearing distance (PV short)
     */
    fun relayPvToSvc(
        senderUuid: UUID,
        senderPlayer: ServerPlayer,
        opusData: ByteArray,
        sequenceNumber: Long,
        distance: Short
    ) {
        val svc = svcAdapter ?: return

        // Skip relay for dual-mod players ??SVC clients already hear them via SVC natively
        if (sessionManager.isDualMod(senderUuid)) return

        val worldName = senderPlayer.level().dimension().identifier().path
        val svcDistance = spatialMapper.pvToSvcDistance(distance, worldName)

        // Let the SVC adapter handle sending to nearby SVC players
        val sent = svc.sendAudioFromExternalPlayer(
            senderUuid,
            senderPlayer,
            opusData,
            sequenceNumber,
            svcDistance
        )

        if (sent) {
            BridgeMetrics.plasmoToSvcFrames.incrementAndGet()
        }

        if (debugProvider()) {
            logger.debug("PV->SVC: ${senderPlayer.gameProfile.name} seq=$sequenceNumber dist=$svcDistance")
        }
    }
}
