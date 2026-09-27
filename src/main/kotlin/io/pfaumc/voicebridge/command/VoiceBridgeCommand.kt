package io.pfaumc.voicebridge.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.permissions.Permissions
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentUtils
import net.minecraft.network.chat.HoverEvent
import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgeMod
import io.pfaumc.voicebridge.session.ModType

object VoiceBridgeCommand {

    private val ACCENT = ChatFormatting.GOLD
    private val LABEL = ChatFormatting.GRAY
    private val VALUE = ChatFormatting.WHITE
    private val CMD = ChatFormatting.YELLOW
    private val SVC_COLOR = ChatFormatting.AQUA
    private val PV_COLOR = ChatFormatting.LIGHT_PURPLE
    private val DUAL_COLOR = ChatFormatting.GREEN

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            // Fabric's Brigadier has no alias support — register both literals
            dispatcher.register(buildCommand("voicebridge"))
            dispatcher.register(buildCommand("vb"))
        }
    }

    private fun buildCommand(root: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(root)
            .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
            .executes { showHelp(it) }
            .then(
                Commands.literal("status")
                    .executes { showStatus(it) }
            )
            .then(
                Commands.literal("players")
                    .executes { showPlayers(it) }
            )
            .then(
                Commands.literal("reload")
                    .executes { reloadConfig(it) }
            )
            .then(
                Commands.literal("debug")
                    .executes { toggleDebug(it, null) }
                    .then(
                        Commands.argument("value", StringArgumentType.word())
                            .suggests { _, builder ->
                                builder.suggest("on").suggest("off").buildFuture()
                            }
                            .executes { ctx ->
                                toggleDebug(ctx, StringArgumentType.getString(ctx, "value"))
                            }
                    )
            )

    private fun hover(text: Component, style: ChatFormatting = LABEL): HoverEvent =
        HoverEvent.ShowText(Component.literal("").append(text).withStyle(style))

    private fun header(text: String): Component =
        Component.literal("══ ").withStyle(ACCENT)
            .append(Component.literal(text).withStyle(ACCENT, ChatFormatting.BOLD))
            .append(Component.literal(" ══").withStyle(ACCENT))

    private fun stat(label: String, value: Any): Component =
        Component.literal("  $label: ").withStyle(LABEL)
            .append(Component.literal(value.toString()).withStyle(VALUE))

    private fun cmdLine(cmd: String, description: String, vararg args: String): Component {
        val line = Component.literal("  ").withStyle(LABEL)
            .append(
                Component.literal(cmd).withStyle(CMD)
                    .withStyle { it.withClickEvent(ClickEvent.SuggestCommand(cmd)) }
                    .withStyle { it.withHoverEvent(hover(Component.literal("Click to paste"))) }
            )
        if (args.isEmpty()) {
            return line.append(Component.literal(" — $description").withStyle(LABEL))
        }
        val argsComponent = args.fold(Component.empty()) { acc, arg ->
            acc.append(Component.literal(" ").withStyle(LABEL))
                .append(
                    Component.literal(arg).withStyle(ChatFormatting.AQUA)
                        .withStyle { it.withClickEvent(ClickEvent.SuggestCommand("$cmd $arg")) }
                        .withStyle { it.withHoverEvent(hover(Component.literal("Click to paste \"$cmd $arg\""))) }
                )
        }
        return line.append(argsComponent).append(Component.literal(" — $description").withStyle(LABEL))
    }

    private fun modTag(session: io.pfaumc.voicebridge.session.BridgeSession): Component {
        if (session.isDualMod()) {
            return Component.literal("[SVC+PV]").withStyle(DUAL_COLOR, ChatFormatting.BOLD)
                .withStyle { it.withHoverEvent(hover(Component.literal("Dual-mod (both installed)"), DUAL_COLOR)) }
        }
        return when (session.modType) {
            ModType.SIMPLE_VOICE_CHAT -> Component.literal("[SVC]").withStyle(SVC_COLOR)
                .withStyle { it.withHoverEvent(hover(Component.literal("Simple Voice Chat"), SVC_COLOR)) }
            ModType.PLASMO_VOICE -> Component.literal("[PV]").withStyle(PV_COLOR)
                .withStyle { it.withHoverEvent(hover(Component.literal("Plasmo Voice"), PV_COLOR)) }
        }
    }

    private fun showStatus(ctx: CommandContext<CommandSourceStack>): Int {
        val debug = VoiceBridgeMod.instance?.isDebug() ?: false
        val passthrough = VoiceBridgeMod.instance?.bridgeConfig?.passthrough ?: true
        ctx.source.sendSuccess(
            {
                ComponentUtils.formatList(
                    listOf(
                        header("Voice Bridge Status"),
                        stat("Active sessions", BridgeMetrics.activeSessions.get()),
                        stat("SVC → PV frames", BridgeMetrics.svcToPlasmoFrames.get()),
                        stat("PV → SVC frames", BridgeMetrics.plasmoToSvcFrames.get()),
                        stat("Dropped frames", BridgeMetrics.droppedFrames.get()),
                        stat("Transcoded frames", BridgeMetrics.transcodingCount.get()),
                        Component.literal("  Debug: ").withStyle(LABEL)
                            .append(
                                Component.literal(
                                    if (debug) "enabled" else "disabled",
                                ).withStyle(if (debug) ChatFormatting.GREEN else ChatFormatting.RED)
                            )
                            .withStyle { it.withHoverEvent(hover(Component.literal("Click to toggle"))) }
                            .withStyle { it.withClickEvent(ClickEvent.RunCommand("/voicebridge debug")) },
                        stat("Passthrough", passthrough),
                    ),
                    Component.literal("\n")
                )
            },
            false
        )
        return Command.SINGLE_SUCCESS
    }

    private fun showPlayers(ctx: CommandContext<CommandSourceStack>): Int {
        val sessions = VoiceBridgeMod.instance?.sessionManager?.getAllSessions()
            ?: emptyList()
        if (sessions.isEmpty()) {
            ctx.source.sendSuccess({ Component.literal("No active voice bridge sessions.").withStyle(LABEL) }, false)
            return Command.SINGLE_SUCCESS
        }
        val lines = mutableListOf(header("Voice Bridge Players (${sessions.size})"))
        for (session in sessions) {
            val activeIndicator = if (session.active) {
                Component.literal(" ●").withStyle(ChatFormatting.GREEN)
                    .withStyle { it.withHoverEvent(hover(Component.literal("Active"), ChatFormatting.GREEN)) }
            } else {
                Component.literal(" ○").withStyle(ChatFormatting.DARK_GRAY)
                    .withStyle { it.withHoverEvent(hover(Component.literal("Inactive"), ChatFormatting.DARK_GRAY)) }
            }
            lines += Component.literal("  ").withStyle(LABEL)
                .append(
                    Component.literal(session.playerName).withStyle(VALUE)
                        .withStyle { it.withHoverEvent(hover(Component.literal("UUID: ${session.playerUuid}"))) }
                )
                .append(Component.literal(" ").withStyle(LABEL))
                .append(modTag(session))
                .append(activeIndicator)
        }
        ctx.source.sendSuccess({ ComponentUtils.formatList(lines, Component.literal("\n")) }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun reloadConfig(ctx: CommandContext<CommandSourceStack>): Int {
        VoiceBridgeMod.instance?.reloadBridgeConfig()
        ctx.source.sendSuccess(
            {
                Component.literal("✔ ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("Config reloaded.").withStyle(VALUE))
            },
            false
        )
        return Command.SINGLE_SUCCESS
    }

    private fun toggleDebug(ctx: CommandContext<CommandSourceStack>, value: String?): Int {
        val currentDebug = VoiceBridgeMod.instance?.isDebug() ?: false
        val enabled = when (value?.lowercase()) {
            "on", "true" -> true
            "off", "false" -> false
            else -> !currentDebug
        }
        VoiceBridgeMod.instance?.setDebug(enabled)
        ctx.source.sendSuccess(
            {
                Component.literal(if (enabled) "✔ " else "✘ ").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED)
                    .append(Component.literal("Debug logging ").withStyle(VALUE))
                    .append(
                        Component.literal(
                            if (enabled) "enabled" else "disabled",
                        ).withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED, ChatFormatting.BOLD)
                    )
                    .append(Component.literal(" (runtime only)").withStyle(LABEL))
            },
            false
        )
        return Command.SINGLE_SUCCESS
    }

    private fun showHelp(ctx: CommandContext<CommandSourceStack>): Int {
        ctx.source.sendSuccess(
            {
                ComponentUtils.formatList(
                    listOf(
                        header("Voice Bridge Commands"),
                        cmdLine("/vb status", "Show bridge status and metrics"),
                        cmdLine("/vb players", "List connected players and their voice mod"),
                        cmdLine("/vb reload", "Reload configuration"),
                        cmdLine("/vb debug", "Toggle debug logging", "on", "off"),
                    ),
                    Component.literal("\n")
                )
            },
            false
        )
        return Command.SINGLE_SUCCESS
    }
}
