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

- Fabric 1.21.5 – 1.21.11 (one jar covers the whole range; compiled against 1.21.5 with
  runtime shims for APIs that diverged in later versions — see `compat/Compat.kt`)
- Fabric API
- Java 21+
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (server-side mod)
- [Plasmo Voice](https://modrinth.com/plugin/plasmo-voice) (server-side mod)

## Installation

1. Install both Simple Voice Chat and Plasmo Voice on your Fabric server
2. Download `voice-bridge-<version>.jar` from [Modrinth](https://modrinth.com/mod/voice-bridge-fabric)
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
  groups-enabled: true # Bridge SVC groups <-> PV group channels (requires pv-addon-groups)

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

## Group bridging

Voice Bridge can bridge **groups** (SVC group chats and Plasmo Voice group channels) so that players
in same-named groups on both mods can talk to each other:

- **Setup**: install the official
  [pv-addon-groups](https://modrinth.com/plugin/pv-addon-groups) server addon alongside Plasmo Voice.
  Group bridging auto-enables when the addon is present (or set `bridge.groups-enabled: true` explicitly).
- **Pairing**: a group is bridged when an SVC group and a PV group (created with `/groups`) share the
  same name. A group created on either mod is automatically mirrored to the other, so players usually
  only need to create one group and everyone joins it from their own mod.
- **Audio model**:
  - An SVC group member speaking is heard by nearby PV members positionally (with name-tag and overlay
    icons) and by distant or cross-dimension PV members at full volume, regardless of distance.
  - A PV group member speaking is heard by all SVC group members at full volume — non-positional, and
    it works across dimensions.
- **Privacy**: bridged group audio stays inside the group; players who are not members hear only
  normal proximity chat.
- **Inspection**: `/vb groups` lists currently bridged groups and their per-side member counts.

### Group limitations

- Password-protected SVC groups and permission-restricted PV groups are not mirrored (the other mod
  cannot enforce or read them); create them manually on both sides if you need them.
- The SVC group UI lists only SVC members, and the PV client group overlay lists only PV members —
  members from the other mod are invisible in those rosters (use `/vb groups` on the server to see both).
- Non-positional (full-volume) group audio does not render a name-tag microphone icon on the receiving
  side; that is a limitation of each mod's static-audio rendering.

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
- **Compat** — cross-version shims (1.21.5–1.21.11); resolves the pre/post-1.21.9 permission APIs
  reflectively at runtime

## Differences from the Paper version

- Config lives in `config/voice-bridge/` instead of `plugins/voice-bridge/`
- World overrides are keyed by dimension identifier (`minecraft:the_nether`) instead of Bukkit world names
- bStats is not included (no Fabric-compatible bStats module); internal `BridgeMetrics` counters remain
- Command permission is op level 2 instead of the `voicebridge.admin` permission node

## Known limitations

- Group bridging requires the [pv-addon-groups](https://modrinth.com/plugin/pv-addon-groups) server addon
  (see the [Group bridging](#group-bridging) section above)
- Transcoding mode is not yet implemented (passthrough only)

The SVC-only edge cases from the original Paper version (audio from PV players not heard
after respawns or cross-dimension teleports) are fixed since 2.1.1 — outbound audio
channels are rebuilt automatically whenever the speaker's entity or dimension changes.

## License

[MIT](LICENSE)
