package io.pfaumc.voicebridge.compat

import net.minecraft.commands.CommandSourceStack
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Cross-version compatibility shims so a single jar can span MC 1.21.5-1.21.11.
 *
 * The jar is compiled against the oldest supported version (1.21.5). Anything that only
 * exists in newer versions (or was removed in them) must be resolved reflectively at
 * runtime. Production servers run under Fabric's intermediary namespace, dev runs use
 * Mojmap names, so every divergent member is looked up by BOTH name candidates.
 *
 * Covered divergences:
 * - Permission check: 1.21.9 replaced CommandSourceStack#hasPermission(int)
 *   (intermediary method_9259, removed in 1.21.9+) with
 *   CommandSourceStack#permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
 *   (method_75037 / class_12099.field_63210).
 */
object Compat {
    private val logger = LoggerFactory.getLogger("VoiceBridge-Compat")

    /**
     * MC 1.21.9 switched authlib's GameProfile from a class (getName()/getId()) to a record
     * (name()/id()). Reading .name directly ties the jar to whichever authlib the compile
     * target shipped, so the other end throws NoSuchMethodError at runtime. Resolve the
     * accessor reflectively instead.
     */
    fun profileName(profile: com.mojang.authlib.GameProfile): String {
        for (accessor in listOf("name", "getName")) {
            try {
                val m = profile.javaClass.getMethod(accessor)
                if (m.parameterCount == 0 && m.returnType == String::class.java) {
                    return (m.invoke(profile) as? String) ?: fallbackProfileName(profile)
                }
            } catch (_: Throwable) {
            }
        }
        return fallbackProfileName(profile)
    }

    private fun fallbackProfileName(profile: com.mojang.authlib.GameProfile): String {
        for (accessor in listOf("id", "getId")) {
            try {
                val m = profile.javaClass.getMethod(accessor)
                if (m.parameterCount == 0) {
                    return m.invoke(profile).toString()
                }
            } catch (_: Throwable) {
            }
        }
        return profile.toString()
    }

    private const val PATH_UNRESOLVED = 0
    private const val PATH_NEW = 1
    private const val PATH_OLD = 2
    private const val PATH_NONE = -1

    @Volatile
    private var resolvedPath: Int = PATH_UNRESOLVED

    private var newGetPermissions: Method? = null
    private var newHasPermission: Method? = null
    private var newGamemasterField: Field? = null
    private var oldHasPermission: Method? = null

    /** Permission level 2 = COMMANDS_GAMEMASTER under the pre-1.21.9 level system. */
    private const val GAMEMASTER_LEVEL = 2

    fun hasGamemasterPermission(source: CommandSourceStack): Boolean {
        resolveIfNeeded()
        return when (resolvedPath) {
            PATH_NEW -> invokeNew(source)
            PATH_OLD -> invokeOld(source)
            else -> false
        }
    }

    private fun resolveIfNeeded() {
        if (resolvedPath != PATH_UNRESOLVED) return
        synchronized(this) {
            if (resolvedPath != PATH_UNRESOLVED) return
            // Old API first: it exists on 1.21.5-1.21.8 and was REMOVED in 1.21.9+,
            // so it resolves without ambiguity. The new Permissions API is only tried
            // when the legacy method is gone, which guarantees we're on 1.21.9+ and
            // avoids intermediary-ID collisions on intermediate versions.
            if (tryResolveOld()) {
                resolvedPath = PATH_OLD
                logger.info("Permission shim: using legacy hasPermission(level) API")
            } else if (tryResolveNew()) {
                resolvedPath = PATH_NEW
                logger.info("Permission shim: using 1.21.9+ Permissions API")
            } else {
                resolvedPath = PATH_NONE
                logger.warn("Permission shim: no known permission API found on this Minecraft version; /voicebridge commands will be denied")
            }
        }
    }

    private fun tryResolveNew(): Boolean {
        // [permsClass, permissionsGetter, gamemasterField] for prod (intermediary) then dev (Mojmap)
        val combos = listOf(
            Triple("net.minecraft.class_12099", "method_75037", "field_63210"),
            Triple("net.minecraft.server.permissions.Permissions", "permissions", "COMMANDS_GAMEMASTER")
        )
        for ((permsClass, getter, field) in combos) {
            try {
                val permsClassObj = Class.forName(permsClass)
                val getterM = CommandSourceStack::class.java.getMethod(getter)
                val permSetClass = getterM.returnType
                val hasPermM = permSetClass.methods.firstOrNull {
                    it.name == "hasPermission" && it.parameterCount == 1
                } ?: continue
                val fieldF = permsClassObj.getField(field)
                if (fieldF.type != hasPermM.parameterTypes[0]) continue
                newGetPermissions = getterM
                newHasPermission = hasPermM
                newGamemasterField = fieldF
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun tryResolveOld(): Boolean {
        for (name in listOf("method_9259", "hasPermission")) {
            try {
                oldHasPermission = CommandSourceStack::class.java.getMethod(
                    name, Int::class.javaPrimitiveType
                )
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun invokeNew(source: CommandSourceStack): Boolean = try {
        val permSet = newGetPermissions!!.invoke(source)
        val gamemaster = newGamemasterField!!.get(null)
        newHasPermission!!.invoke(permSet, gamemaster) as Boolean
    } catch (t: Throwable) {
        logger.warn("Permission shim (new API) failed: ${t.message}")
        false
    }

    private fun invokeOld(source: CommandSourceStack): Boolean = try {
        oldHasPermission!!.invoke(source, GAMEMASTER_LEVEL) as Boolean
    } catch (t: Throwable) {
        logger.warn("Permission shim (legacy API) failed: ${t.message}")
        false
    }
}
