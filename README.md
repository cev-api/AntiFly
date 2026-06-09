# AntiFly

![LOGO](https://i.imgur.com/WQqocWS.png)

Lightweight Paper + Fabric flight-control plugin by CevAPI.

AntiFly is not a full anti-cheat. It focuses on flight and movement abuse with layered checks:
- server-authorized flight state
- server-side ground/support truth
- buffered air/hover/anti-kick suspicion
- disciplined setbacks to last known valid support
- Elytra-specific movement and boost sanity checks

## Platforms
- Paper: 1.21.x -> 26.1.2
- Fabric: 26.1.2 (Fabric API 0.146.1)

## Build
```bash
./gradlew build
```

Artifacts:
- `paper/build/libs/AntiFly-paper-<version>.jar`
- `fabric/build/libs/AntiFly-fabric-<version>.jar`

## Install
- Paper: drop the Paper jar into `plugins/`
- Fabric: drop the Fabric jar into `mods/`

## Commands
### Paper
Requires op or `antifly.admin`.

Core:
- `/antifly enable`
- `/antifly disable`
- `/antifly status`
- `/antifly help`
- `/antifly exempt <player>`
- `/antifly unexempt <player>`
- `/antifly disabledworlds <worldName> <true|false>`
- `/antifly set`
- `/antifly set <key>`
- `/antifly set <key> <value>`
- `/antifly reset <player>`

Alerts:
- `/antifly alerts <off|game|console|both>`
- `game` alerts go to ops and users with `antifly.alerts`

Debug:
- `/antifly debug on`
- `/antifly debug off`
- `/antifly debug`
- Shows live action-bar telemetry (mode, speed vs limits, key buffers)

### Fabric
Requires moderator-level command permission.
- `/antifly` command family is available with equivalent core controls.
- `/antifly disabledworlds <worldName> <true|false>`

## Primary settings keys (Paper)
- `groundWalkMax`
- `groundMountedMax`
- `waterMax`
- `waterVerticalMax`
- `maxAirHorizontal`
- `maxAirVertical`
- `bufferDecay`
- `horizontalBufferLimit`
- `verticalBufferLimit`
- `hoverBufferLimit`
- `noFallDetectionEnabled`
- `airNonFallTicksLimit`
- `antiKickWindowTicks`
- `antiKickMinDescent`
- `setbackCooldownMs`
- `elytraEnabled`
- `elytraBoostGraceTicks`
- `elytraStallTicks`
- `elytraMovementBufferLimit`
- `elytraDurabilityCheckEnabled`
- `elytraMaxRocketHorizontal`
- `elytraMaxRocketUp`
- `elytraNoRocketSustainableHorizontal`
- `elytraMaxNoRocketUp`

Legacy aliases kept for backward compatibility:
- `groundSpeed`, `groundSpeedWalking`, `groundSpeedMounted`
- `waterSpeed`, `waterVertical`
- `airSpeed`, `airVertical`, `airNonFallTicks`
- `elytraMovementLimit`

## Notes on Elytra
- Rocket-assisted glide is tracked separately from no-rocket glide.
- No-rocket controlled upward/flat cruise behavior is treated as suspicious and can be blocked.
- Rare fluid-exit and early-glide pull-up transitions are given short grace windows to reduce false positives.

## Permissions (Paper)
- `antifly.admin` (default: op)
- `antifly.alerts` (default: op)

## Config locations
- Paper: `plugins/AntiFly/config.yml`
- Fabric: `config/antifly.json`

## World-specific disable
- Paper config supports `disabledWorlds: []`
- Fabric config supports `"disabledWorlds": []`
- Add exact world names to disable AntiFly in those worlds, for example `world_nether`, `world_the_end`, or any custom multiverse world name
- `/antifly status` shows the currently disabled worlds

## Modrinth version check
- Project slug: `antiflight`
- `/antifly status` performs a live check
- Startup check warns ops/admins if outdated
