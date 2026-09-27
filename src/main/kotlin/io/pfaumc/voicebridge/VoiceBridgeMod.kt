package io.pfaumc.voicebridge

import io.pfaumc.voicebridge.adapter.PvAdapter
import io.pfaumc.voicebridge.adapter.SvcAdapter
import io.pfaumc.voicebridge.command.VoiceBridgeCommand
import io.pfaumc.voicebridge.config.BridgeConfig
import io.pfaumc.voicebridge.relay.AudioRelay
import io.pfaumc.voicebridge.session.ModType
import io.pfaumc.voicebridge.session.SessionManager
import io.pfaumc.voicebridge.spatial.SpatialMapper
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class VoiceBridgeMod : ModInitializer {

    lateinit var bridgeConfig: BridgeConfig
        private set
    lateinit var sessionManager: SessionManager
        private set
    lateinit var spatialMapper: SpatialMapper
        private set
    lateinit var audioRelay: AudioRelay
        private set

    var server: MinecraftServer? = null
        private set

    private var pvAdapter: PvAdapter? = null

    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName("VoiceBridge")
    )

    override fun onInitialize() {
        instance = this

        // Load config
        bridgeConfig = BridgeConfig.load(configDir)
        if (!bridgeConfig.enabled) {
            logger.info("Voice Bridge is disabled in config")
            return
        }

        // Initialize core components
        sessionManager = SessionManager()
        spatialMapper = SpatialMapper(bridgeConfig)
        audioRelay = AudioRelay(sessionManager, spatialMapper, bridgeConfig, ::isDebug)

        // Initialize adapters.
        // SVC instantiates SvcAdapter itself via the "voicechat" entrypoint in fabric.mod.json;
        // the instance becomes available in SvcAdapter.instance once SVC initializes its plugins.
        val svcAvailable = initSvcAdapter()

        // Load the PV addon via PV's AddonsLoader (documented Fabric pattern)
        val pvAvailable = initPvAdapter()

        if (!svcAvailable && !pvAvailable) {
            logger.warn("Neither Simple Voice Chat nor Plasmo Voice detected. Bridge has nothing to do.")
            return
        }
        if (!svcAvailable) {
            logger.warn("Simple Voice Chat not detected. Bridge will not function without both mods.")
            return
        }
        if (!pvAvailable) {
            logger.warn("Plasmo Voice not detected. Bridge will not function without both mods.")
            return
        }

        // Register commands via Brigadier
        VoiceBridgeCommand.register()

        // Track the server instance for player lookups
        ServerLifecycleEvents.SERVER_STARTING.register { serverInstance ->
            server = serverInstance
            // Re-arm the cleanup loop if this is a restart within the same JVM
            if (initialized) startCleanupLoop()
        }

        // Shut down when the server stops
        ServerLifecycleEvents.SERVER_STOPPING.register {
            disable()
        }

        initialized = true

        logger.info("Voice Bridge enabled ??bridging Simple Voice Chat <-> Plasmo Voice")
        BridgeMetrics.log(logger)
    }

    private fun startCleanupLoop() {
        scope.launch {
            while (isActive) {
                delay(5.seconds)
                sessionManager.cleanup()
            }
        }
    }

    fun reloadBridgeConfig() {
        bridgeConfig = BridgeConfig.load(configDir)
        spatialMapper.config = bridgeConfig
        logger.info("Voice Bridge config reloaded")
    }

    private fun disable() {
        pvAdapter?.shutdown()
        SvcAdapter.instance?.shutdown()
        sessionManager.clear()
        scope.cancel()
        logger.info("Voice Bridge disabled")
    }

    private fun initSvcAdapter(): Boolean {
        return try {
            Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin")
            logger.info("Simple Voice Chat detected ??SVC adapter will be initialized by SVC")
            true
        } catch (e: ClassNotFoundException) {
            logger.info("Simple Voice Chat not found on classpath")
            false
        }
    }

    private fun initPvAdapter(): Boolean {
        return try {
            Class.forName("su.plo.voice.api.server.PlasmoVoiceServer")
            pvAdapter = PvAdapter(this)
            logger.info("Plasmo Voice detected ??PV adapter initialized")
            true
        } catch (e: ClassNotFoundException) {
            logger.info("Plasmo Voice not found on classpath")
            false
        }
    }

    @Volatile
    private var debugOverride: Boolean? = null

    fun isDebug(): Boolean = debugOverride ?: bridgeConfig.debug

    fun setDebug(enabled: Boolean) {
        debugOverride = enabled
        logger.info("Debug logging ${if (enabled) "enabled" else "disabled"} (runtime only)")
    }

    val configDir: Path
        get() = FabricLoader.getInstance().configDir.resolve("voice-bridge")

    companion object {
        var instance: VoiceBridgeMod? = null
            private set

        var initialized: Boolean = false
            private set

        val logger: Logger = LoggerFactory.getLogger("voice-bridge")

        fun isReady(): Boolean = instance != null && initialized
    }
}
