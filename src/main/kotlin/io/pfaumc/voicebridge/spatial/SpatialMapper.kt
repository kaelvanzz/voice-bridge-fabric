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
     * Effective voice distance for a world:
     * - if the world has a configured override, that value replaces the default range
     * - otherwise the speaker's own distance is kept (falling back to the default when
     *   the source provides none)
     * - everything is clamped to [maxDistance]
     */
    fun getEffectiveDistance(distance: Double, worldName: String? = null): Double {
        val override = worldName?.let { config.worldOverrides[it] }
        val base = override ?: distance.takeIf { it > 0 } ?: config.defaultDistance
        return base.coerceAtMost(config.maxDistance)
    }

    /**
     * Calculate whisper distance from normal distance.
     */
    fun whisperDistance(normalDistance: Double): Double {
        return normalDistance * config.whisperMultiplier
    }
}
