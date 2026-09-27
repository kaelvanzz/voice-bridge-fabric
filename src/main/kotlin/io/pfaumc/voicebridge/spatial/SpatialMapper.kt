package io.pfaumc.voicebridge.spatial

import io.pfaumc.voicebridge.config.BridgeConfig

class SpatialMapper(var config: BridgeConfig) {

    /**
     * Convert SVC distance (Float) to PV distance (Short).
     */
    fun svcToPvDistance(svcDistance: Float, worldName: String? = null): Short {
        val effective = getEffectiveDistance(svcDistance.toDouble(), worldName)
        return effective.toInt().coerceIn(1, Short.MAX_VALUE.toInt()).toShort()
    }

    /**
     * Convert PV distance (Short) to SVC distance (Float).
     */
    fun pvToSvcDistance(pvDistance: Short, worldName: String? = null): Float {
        return getEffectiveDistance(pvDistance.toDouble(), worldName).toFloat()
    }

    /**
     * Get the effective distance, applying per-world overrides if configured.
     */
    fun getEffectiveDistance(distance: Double, worldName: String? = null): Double {
        val maxDistance = config.maxDistance
        val worldOverride = worldName?.let { config.worldOverrides[it] }
        val effectiveDefault = worldOverride ?: config.defaultDistance

        // If the incoming distance is valid, use it (clamped to max).
        // Otherwise fall back to configured default.
        return if (distance > 0) {
            distance.coerceAtMost(maxDistance)
        } else {
            effectiveDefault.coerceAtMost(maxDistance)
        }
    }

    /**
     * Calculate whisper distance from normal distance.
     */
    fun whisperDistance(normalDistance: Double): Double {
        return normalDistance * config.whisperMultiplier
    }
}
