# Voice Bridge (Fabric)

A server-side [Fabric](https://fabricmc.net/) mod that bridges proximity voice chat
between [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)
and [Plasmo Voice](https://modrinth.com/plugin/plasmo-voice), allowing players using different voice mods to hear each
other.

A port of the original Paper plugin [PfauMC/voice-bridge](https://github.com/PfauMC/voice-bridge) to Fabric.

## How it works

Both Simple Voice Chat (SVC) and Plasmo Voice (PV) encode audio with Opus at 48 kHz, mono, 20 ms frames. Voice Bridge
intercepts audio packets from each mod's API and relays them to the other — no transcoding required. The result is
low-latency, cross-mod proximity voice chat with minimal CPU overhead.

```
SVC Player ──► SVC API ──► Voice Bridge ──► PV API ──► PV Player
PV Player  ──► PV API  ──► Voice Bridge ──► SVC API ──► SVC Player
```

## Requirements

- Fabric 1.21.11 (Minecraft 1.21.11 only — the mod uses APIs introduced in 1.21.11)
- Fabric API
- Java 21+
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (server-side mod)
- [Plasmo Voice](https://modrinth.com/plugin/plasmo-voice) (server-side mod)

## Installation

1. Install both Simple Voice Chat and Plasmo Voice on your Fabric server
2. Download `voice-bridge-<version>.jar` from [Modrinth](https://modrinth.com/plugin/voice-bridge)
3. Place it in your server's `mods/` directory
4. Restart the server

## Building from source

```shell
./gradlew build
```

The mod jar will be in `build/libs/` (the one without the `-all` classifier).

### Running a dev server

```shell
./gradlew runServer
```

## Configuration

A `config.yml` is generated on first run in `config/voice-bridge/`:

```yaml
bridge:
  enabled: true       # Enable/disable the bridge
  debug: false        # Enable debug logging

proximity:
  default-distance: 48.0      # Default voice range in blocks
  whisper-multiplier: 0.33    # Whisper range = distance * multiplier
  max-distance: 128.0         # Maximum allowed voice distance

audio:
  passthrough: true            # Opus passthrough (recommended)
  force-transcode: false       # Force transcoding (not yet implemented)
```

### Per-world distance overrides

You can override the default voice distance for specific worlds:

```yaml
worlds:
  minecraft:the_nether:
    default-distance: 24.0
  minecraft:the_end:
    default-distance: 64.0
```

Note: on Fabric, world keys use the `namespace:path` form of the dimension identifier
(e.g. `minecraft:overworld`, `minecraft:the_nether`).

## Commands

| Command                        | Description                             |
|--------------------------------|-----------------------------------------|
| `/voicebridge status`          | Show bridge status, metrics, and config |
| `/voicebridge players`         | List active bridged sessions            |
| `/voicebridge reload`          | Reload configuration                    |
| `/voicebridge debug [on\|off]` | Toggle debug logging                    |

Alias: `/vb`

Permission: vanilla game-master commands permission (`Permissions.COMMANDS_GAMEMASTER`; server ops granted via
`/op` have all command permissions by default)

## Architecture

```
┌────────────────────────────────────────────────┐
│                VoiceBridgeMod                  │
├───────────┬───────────┬────────────┬───────────┤
│ SvcAdapter│ PvAdapter │ AudioRelay │  Session  │
│           │           │            │  Manager  │
├───────────┴───────────┴────────────┴───────────┤
│  SpatialMapper │ BridgeConfig │ BridgeMetrics  │
└────────────────────────────────────────────────┘
```

- **SvcAdapter** — integrates with Simple Voice Chat via `VoicechatPlugin`, registered through the `voicechat`
  entrypoint in `fabric.mod.json`
- **PvAdapter** — integrates with Plasmo Voice via `@Addon` / `AddonInitializer`, loaded through
  `PlasmoVoiceServer.getAddonsLoader()` on initialize
- **AudioRelay** — central router that forwards audio frames between adapters
- **SessionManager** — tracks which mod each player is using
- **SpatialMapper** — converts between SVC float distances and PV short distances

## Differences from the Paper version

- Config lives in `config/voice-bridge/` instead of `plugins/voice-bridge/`
- World overrides are keyed by dimension identifier (`minecraft:the_nether`) instead of Bukkit world names
- bStats is not included (no Fabric-compatible bStats module); internal `BridgeMetrics` counters remain
- Command permission is op level 2 instead of the `voicebridge.admin` permission node

## Known limitations

- Group/channel bridging is not supported (proximity chat only)
- Transcoding mode is not yet implemented (passthrough only)
- SVC-only players may not receive audio from PV players in some edge cases

## License

[MIT](LICENSE)
