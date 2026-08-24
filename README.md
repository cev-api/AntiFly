# AntiFly

![LOGO](https://i.imgur.com/WQqocWS.png)

Lightweight Paper + Fabric flight-control plugin by CevAPI.

Current release: `1.1.3`

AntiFly is not a full anti-cheat - it focuses on flight/movement abuse with layered checks:
- server-side ground/support truth (never trusts client `onGround`)
- buffered air/hover/anti-kick suspicion + sustained-air detection
- disciplined setbacks to last known valid support
- Elytra checks that only flag what vanilla physics can't do (speed, instant vertical, stall)
- teleport-aware rebasing; `/tp`, pearls, chorus, portals never trigger setbacks
- Hunger Mode, a configurable food/health tax instead of hard blocks

## Platforms
- Paper: 1.21.1-26.2 (Folia-supported)
- Fabric: 1.21.11 / 26.1.2 / 26.2

## Build
```bash
./gradlew build
```
Artifacts land in `paper/build/libs/` and each `fabric_*/build/libs/`.

## Install
- Paper: drop the jar into `plugins/`
- Fabric: drop the jar into `mods/`

## Commands
Same set on Paper & Fabric. Requires op / `antifly.admin` on Paper, moderator-level on Fabric.

- `/antifly enable|disable|status|help`
- `/antifly hungermode <on|off>`
- `/antifly set` - lists every tunable key with its current value
- `/antifly set <key>` - read one value (accepts canonical keys, unambiguous section-child names, and dotted YAML paths)
- `/antifly set <key> <value>` - set one (applies instantly, auto-saves)
- `/antifly alerts <off|game|console|both>`
- `/antifly debug <on|off>`
- `/antifly exempt <player>` / `/antifly unexempt <player>`
- `/antifly reset <player>`
- `/antifly disabledworlds <worldName> <true|false>`

## Tuning keys
All adjustable live via `/antifly set`. Defaults in brackets.

### Movement
`groundWalkMax` (0.67) · `groundMountedMax` (0.75) · `waterMax` (0.55) · `waterVerticalMax` (0.7) · `boatMaxHorizontal` (0.85) · `maxAirHorizontal` (1.8) · `maxAirVertical` (1.0) · `sustainedAirTicksLimit` (150)

### Elytra
Same keys on both platforms. On Fabric the rocket/no-rocket variants map to the single caps `elytraMaxHorizontal` (6.0) and `elytraMaxUp` (4.0); on Paper they're independent.
- `elytraMaxHorizontal` / `elytraNoRocketSustainableHorizontal` / `elytraMaxRocketHorizontal`
- `elytraMaxUp` / `elytraMaxNoRocketUp` / `elytraMaxRocketUp`
- `elytraEnabled` · `elytraBoostGraceTicks` · `elytraStallTicks` · `elytraMovementBufferLimit`

### Hunger Mode
- `hungerModeMaxBlocksPerSecond` (200) · `hungerModeHungerPerSecondAtMaxSpeed` (10) · `hungerModeRocketGraceTicks` (80) · `hungerModeAirborneMinimumBlocksPerSecond` (20)
- `hungerModeFlightDamageEnabled` (true) · `hungerModeFlightDamageAfterSeconds` (20) · `hungerModeFlightDamageAfterHungerSeconds` (30) · `hungerModeFlightDamagePerSecond` (1)
- `hungerModeElytraFoodEnabled` (true) · `hungerModeElytraFoodMultiplier` (0.5) · `hungerModeElytraSpeedThresholdBps` (50) · `hungerModeElytraNoRocketAfterSeconds` (45) · `hungerModeElytraDamageEnabled` (true) · `hungerModeRocketResetsDamage` (true)

## Hunger Mode
- `/antifly hungermode on` replaces hard movement blocks with a food-and-health penalty. It uses server-accepted movement and does not trust client `onGround`.
- Above the airborne floor, drain scales quadratically with horizontal speed. The airborne minimum is a linear hover baseline: with `maxBlocksPerSecond: 200`, `airborneMinimumBlocksPerSecond: 20`, and `hungerPerSecondAtMaxSpeed: 5`, idle hovering costs 0.5 food points/sec.
- Food loss is applied continuously (at most one food point per tick); landing or regaining support immediately clears any pending flight debt.
- Unsupported non-Elytra flight normally starts health damage after 20 seconds. Reaching food level 0 escalates immediately to flight damage; `flightDamagePerSecond` is measured in health points (1.0 = half a heart), and is doubled at zero food (so 1.0 = one full heart). Flight damage is generic, so it stacks with vanilla starvation.
- Elytra is only punished when exploiting: above the speed threshold, hover-hacking, or gliding too long without a rocket. Rocket-assisted vanilla gliding is free; Elytra health damage otherwise starts after its configured active-drain period.

## Notes on Elytra
- Checks only block what vanilla can't do: sustained speed beyond ~6 b/t, instant climbs beyond ~4 b/t, and stall-hovering. Steep dives, pull-ups, rocket boosts and crashing into obstacles are all allowed.
- Repeated ground-flag spoofing can't refresh landing grace; sustained flat-air movement is blocked after 20 air ticks at 0.45 b/t.

## Permissions (Paper)
- `antifly.admin` (default: op) · `antifly.alerts` (default: op)

## Config
- Paper: `plugins/AntiFly/config.yml` · Fabric: `config/antifly.json`
- Disable AntiFly per world with `disabledWorlds: []` on either platform
- Modrinth check (slug `antiflight`): `/antifly status` + startup warning when outdated
