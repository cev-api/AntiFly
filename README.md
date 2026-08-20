# AntiFly

![LOGO](https://i.imgur.com/WQqocWS.png)

Lightweight Paper + Fabric flight-control plugin by CevAPI.

Current release: `1.1.2`

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
- `/antifly set <key>` - read one value
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
- `/antifly hungermode on` - replaces hard blocks with a food drain. Hovering always drains slowly, and client `onGround` spoofing can't bypass it.
- Drain scales quadratically with horizontal speed (cap 200 BPS = 10 food/s). Rocket boost = free for 80 ticks; gliding pays by its real speed (no unfair elytra tax).
- After 20s of continuous unsupported flight (not gliding), real health damage kicks in (generic damage, in full-heart chunks every 2s, every 4s while descending). Health damage always follows hunger: the timer only counts while hunger is actively draining, so the hunger phase comes first.
- Elytra is only punished when exploiting: flying over the speed threshold, hover-hacking, or gliding for a long time without any rocket (45s). Vanilla gliding (with rockets) is completely free - no food drain, no health damage. For elytra, health damage only starts after hunger has been actively draining for 30s.

## Notes on Elytra
- Checks only block what vanilla can't do: sustained speed beyond ~6 b/t, instant climbs beyond ~4 b/t, and stall-hovering. Steep dives, pull-ups, rocket boosts and crashing into obstacles are all allowed.
- Repeated ground-flag spoofing can't refresh landing grace; sustained flat-air movement is blocked after 20 air ticks at 0.45 b/t.

## Permissions (Paper)
- `antifly.admin` (default: op) · `antifly.alerts` (default: op)

## Config
- Paper: `plugins/AntiFly/config.yml` · Fabric: `config/antifly.json`
- Disable AntiFly per world with `disabledWorlds: []` on either platform
- Modrinth check (slug `antiflight`): `/antifly status` + startup warning when outdated
