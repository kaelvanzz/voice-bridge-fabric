package io.pfaumc.voicebridge.config

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

data class BridgeConfig(
    val enabled: Boolean = true,
    val debug: Boolean = false,
    val defaultDistance: Double = 48.0,
    val whisperMultiplier: Double = 0.33,
    val maxDistance: Double = 128.0,
    val passthrough: Boolean = true,
    val forceTranscode: Boolean = false,
    val repacing: Boolean = true,
    val repacingJitterFrames: Int = 2,
    val worldOverrides: Map<String, Double> = emptyMap()
) {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger("VoiceBridge")

        @Suppress("UNCHECKED_CAST")
        fun load(dataFolder: Path): BridgeConfig {
            val configFile = dataFolder.resolve("config.yml")

            if (!Files.exists(configFile)) {
                Files.createDirectories(dataFolder)
                Files.writeString(configFile, DEFAULT_CONFIG)
                logger.info("Created default config at ${configFile.toAbsolutePath()}")
            }

            val root: Map<String, Any> =
                Yaml().load<Map<String, Any>>(Files.readString(configFile)) ?: emptyMap()

            fun section(name: String): Map<String, Any> =
                root[name] as? Map<String, Any> ?: emptyMap()

            fun Map<String, Any>.bool(key: String, def: Boolean): Boolean =
                (this[key] as? Boolean) ?: def

            fun Map<String, Any>.double(key: String, def: Double): Double =
                (this[key] as? Number)?.toDouble() ?: def

            fun Map<String, Any>.int(key: String, def: Int): Int =
                (this[key] as? Number)?.toInt() ?: def

            val worldsSection = section("worlds")
            val worldOverrides = mutableMapOf<String, Double>()
            worldsSection.keys.forEach { worldName ->
                (worldsSection[worldName] as? Map<String, Any>)?.let { world ->
                    val distance = world.double("default-distance", -1.0)
                    if (distance > 0) {
                        worldOverrides[worldName] = distance
                    }
                }
            }

            val bridge = section("bridge")
            val proximity = section("proximity")
            val audio = section("audio")

            return BridgeConfig(
                enabled = bridge.bool("enabled", true),
                debug = bridge.bool("debug", false),
                defaultDistance = proximity.double("default-distance", 48.0),
                whisperMultiplier = proximity.double("whisper-multiplier", 0.33),
                maxDistance = proximity.double("max-distance", 128.0),
                passthrough = audio.bool("passthrough", true),
                forceTranscode = audio.bool("force-transcode", false),
                repacing = audio.bool("repacing", true),
                repacingJitterFrames = audio.int("jitter-frames", 2),
                worldOverrides = worldOverrides
            )
        }

        private val DEFAULT_CONFIG = """
            # Voice Bridge Configuration
            bridge:
              enabled: true
              debug: false

            proximity:
              # Default voice distance in blocks
              default-distance: 48.0
              # Whisper distance = normal distance * this multiplier
              whisper-multiplier: 0.33
              # Maximum allowed voice distance
              max-distance: 128.0

            audio:
              # Pass Opus frames through without re-encoding (recommended, lowest latency)
              passthrough: true
              # Force decode and re-encode all frames (higher CPU, use only if codec mismatch)
              force-transcode: false
              # Smooth out bursty Simple Voice Chat frame delivery before forwarding to Plasmo
              # Voice. Holds a small lead of frames per speaker and releases them at a steady 20ms
              # cadence so PV clients don't underflow their jitter buffer and crackle.
              repacing: true
              # Frames to pre-buffer before draining (each frame is 20ms). Higher = smoother under
              # jitter but more latency. 2 = ~40ms added.
              jitter-frames: 2

            # Per-world distance overrides (optional)
            # worlds:
            #   world_nether:
            #     default-distance: 24.0
            #   world_the_end:
            #     default-distance: 64.0
        """.trimIndent()
    }
}
