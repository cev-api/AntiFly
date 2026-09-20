# AntiFly configuration

Everything here is tunable on a running server. Nothing in this document requires a restart or a plugin update, and no setting needs to be edited by hand.

## Contents
- [Config files](#config-files)
- [Live tuning with /antifly set](#live-tuning-with-antifly-set)
- [Full command reference](#full-command-reference)
- [Movement](#movement)
- [Legitimate causes](#legitimate-causes)
- [Vehicle fall](#vehicle-fall)
- [Elytra](#elytra)
- [Hunger Mode settings](#hunger-mode-settings)
- [Void access](#void-access)
- [Platform differences](#platform-differences)
- [World toggles and version check](#world-toggles-and-version-check)
- [Permissions](#permissions)

## Config files
- **Paper / Bukkit family:** `plugins/AntiFly/config.yml`
- **Fabric:** `config/antifly.json`

Both platforms expose the same concepts under the same key names, so a tuning guide found in Discord applies to either.

If you change a shipped default in code, existing installs keep their stored value. AntiFly versions its config and migrates old defaults forward automatically, but a key you have deliberately tuned is never overwritten.

## Live tuning with /antifly set
```
/antifly set                    # list every key with its current value
/antifly set <key>              # read one value
/antifly set <key> <value>      # change one value (applies instantly, auto-saves)
```

Keys can be given in any of these forms:
- the canonical key, e.g. `hungerModeHungerPerSecondAtMaxSpeed`
- the snake_case alias, e.g. `hunger_mode_hunger_per_second_at_max_speed`
- an unambiguous section-child name
- a full dotted YAML path, e.g. `hungerMode.hungerPerSecondAtMaxSpeed`

`/antifly set` with no arguments always reflects what the running server actually supports, so you never have to guess whether a key exists on your platform.

## Full command reference
The command set is identical on Paper and Fabric. On Paper it requires op or `antifly.admin`; on Fabric it requires moderator-level permissions.

| Command | Effect |
| --- | --- |
| `/antifly enable\|disable` | Turn the checks on or off globally |
| `/antifly status` | Show current state, and check Modrinth for a newer release |
| `/antifly help` | List commands |
| `/antifly hungermode <on\|off>` | Swap hard movement blocks for the hunger/health penalty |
| `/antifly voidaccess <on\|off>` | Allow or block void entry (default on) |
| `/antifly reload` | Re-read the config from disk, no restart |
| `/antifly set <key> [value]` | List, read or change any tuning key |
| `/antifly alerts <off\|game\|console\|both>` | Where violation alerts are delivered |
| `/antifly debug <on\|off>` | Verbose per-check output |
| `/antifly exempt <player>` / `/antifly unexempt <player>` | Exempt a player from every check, or undo it |
| `/antifly reset <player>` | Clear a player's accumulated suspicion and state |
| `/antifly disabledworlds <worldName> <true\|false>` | Toggle checks in a single world |

## Movement
Defaults in brackets.

`groundWalkMax` (0.67) · `groundMountedMax` (0.75) · `waterMax` (0.55) · `waterVerticalMax` (0.7) · `boatMaxHorizontal` (0.85) · `maxAirHorizontal` (1.8) · `maxAirVertical` (1.0) · `sustainedAirTicksLimit` (150) · `sustainedAirMinDescent` (40)

- Horizontal and vertical speed limits are per tick, expressed in blocks per tick.
- `sustainedAirTicksLimit` is the backstop that catches any sustained unsupported flight regardless of speed or bobbing. It is the check that survives every attempt to fly just under the speed caps.
- `sustainedAirMinDescent` exists so a genuine long fall, which is a real descent, resets the airborne timer instead of counting toward the limit.

## Legitimate causes
`impulseGraceTicks` (20) · `groundSpoofTicksLimit` (3) · `repeatOffenderAlertCount` (10)

- **Flight or speed caused by something outside the player's control is never punished.** A wind charge, TNT or bed blast, mace smash or mob knockback produces a velocity gain no legitimate input can match (a vanilla jump is only 0.42 blocks/tick), so the checks stand down for `impulseGraceTicks`. Landing only removes velocity, so it never qualifies. Set to `0` to disable.
- **Water movement is measured against the vanilla speed modifiers** (sprinting, Depth Strider, Dolphin's Grace), and ground movement against sprinting and Speed potions, so enchanted or buffed movement is not flagged.
- `groundSpoofTicksLimit` is the number of consecutive ticks a client may claim solid ground the server cannot find before it counts as a no-fall style spoof.
- `repeatOffenderAlertCount` is how many checks a player may trip within 5 minutes before staff get a follow-up warning. Setbacks deliberately do not clear this count, so someone who is corrected and immediately continues is distinguishable from a one-off lag spike. The value is clamped to a minimum of `1`.

## Vehicle fall
`vehicleFallMinDescent` (-0.04) · `vehicleFallMaxHorizontal` (0.40) · `vehicleFallTicksMax` (60)

Vehicles are scored on their own movement, and a rider is never judged on the seat snap, release bump or wave bob. A boat that is genuinely afloat or resting on support is exempt.

## Elytra
The Elytra verifier samples accepted movement once per server tick and predicts the next unpowered glide step from pitch, yaw, and prior velocity. It accumulates residual motion, excess energy relative to that prediction, unnaturally constant speed, and sharp turning. It only acts when multiple channels agree. Rocket boosts, Riptide, server velocity events (Paper), damage impulses (Fabric), relevant potion effects, and collisions reset or pause the model.

Older speed and altitude counters remain available for debugging and server tuning but no longer stop a glide on their own; powerful rockets are not subject to a fixed Elytra speed cap.

- `elytraMaxHorizontal` / `elytraNoRocketSustainableHorizontal` / `elytraMaxRocketHorizontal`
- `elytraMaxUp` / `elytraMaxNoRocketUp` / `elytraMaxRocketUp`
- `elytraEnabled` · `elytraBoostGraceTicks` · `elytraStallTicks` · `elytraMovementBufferLimit`
- `elytraNoRocketMaxAscent` (6.0; legacy diagnostic) · `elytraGlideSuppressionTicks` (60)
- `elytraVerifierMode` (`enforce`, `observe`, or `off`)
- `elytraVerifierGravity` · `elytraVerifierResidualAllowance` · `elytraVerifierResidualPerSpeed` · `elytraVerifierEnergyAllowance` · `elytraVerifierSteadySpeedDeviation` · `elytraVerifierMinimumSteadySpeed` · `elytraVerifierSteadyWindowTicks` · `elytraVerifierEvidenceWindowTicks` · `elytraVerifierMinimumEvidenceTicks` · `elytraVerifierMinimumChannels` · `elytraVerifierConfidenceThreshold` · `elytraVerifierSharpTurnDegrees`

### Catching elytra flight hacks
Use `/antifly set elytraVerifierMode observe` with `/antifly debug on` to see evidence without setbacks, then switch to `enforce` after checking clean and exploit flights on your server.

The predictor matches the glide coefficients in Minecraft 1.21.11 through 26.3; forks with altered movement can widen the verifier tolerances. A build check alone cannot establish runtime compatibility or guarantee no false positives on custom physics.

### Penalty that cannot be undone
Catching a hack is useless if the client just re-enables flight. A hacked client re-sends `START_FALL_FLYING` on the very next tick, which would undo a one-shot stop. Every elytra violation therefore starts a `elytraGlideSuppressionTicks` window (default 60 ticks / 3s) during which gliding is refused outright - on Paper the toggle event is cancelled, and on Fabric the glider is stopped every tick until the window expires.

### Notes on vanilla elytra
- Checks only block what vanilla cannot do: sustained speed beyond ~6 b/t, instant climbs beyond ~4 b/t, and stall-hovering. Steep dives, pull-ups, rocket boosts and crashing into obstacles are all allowed.
- Repeated ground-flag spoofing cannot refresh landing grace; sustained flat-air movement is blocked after 20 air ticks at 0.45 b/t.

## Hunger Mode settings
- `hungerModeMaxBlocksPerSecond` (200) · `hungerModeHungerPerSecondAtMaxSpeed` (10) · `hungerModeRocketGraceTicks` (80) · `hungerModeAirborneMinimumBlocksPerSecond` (20)
- `hungerModeFlightDamageEnabled` (true) · `hungerModeFlightDamageAfterSeconds` (20) · `hungerModeFlightDamageAfterHungerSeconds` (30) · `hungerModeFlightDamagePerSecond` (1)
- `hungerModeElytraFoodEnabled` (true) · `hungerModeElytraFoodMultiplier` (0.5) · `hungerModeElytraSpeedThresholdBps` (50) · `hungerModeElytraNoRocketAfterSeconds` (45) · `hungerModeElytraDamageEnabled` (true) · `hungerModeRocketResetsDamage` (true)

Behaviour:

- `/antifly hungermode on` replaces hard movement blocks with a penalty: food is drained first, then health damage starts once the flight has lasted long enough (or the player is starving). Vanilla unpowered gliding and rocket-assisted flight stay exempt unless their movement provides exploit evidence.
- Food drain is speed based with a minimum airborne rate, so an idle hoverer still pays.
- Damage ticks slowly (every 2s, every 4s while descending) so a landing attempt is always survivable.
- `hungerModeHungerPerSecondAtMaxSpeed` is the drain at the speed ceiling; the effective rate scales down with measured speed and never falls below `hungerModeAirborneMinimumBlocksPerSecond` worth of drain while airborne.
- `hungerModeFlightDamageAfterSeconds` is the non-elytra gate; `hungerModeFlightDamageAfterHungerSeconds` is the elytra gate and only counts time spent actively draining food.
- The void is the one exception: there Hunger Mode skips the food phase and charges health directly (see below).

## Void access
- `/antifly voidaccess on` (default) permits access to the Nether roof (Y ≥ 128), the Nether void and the Overworld void.
- With `voidaccess off` the behaviour depends on Hunger Mode:
  - **Hunger Mode off:** players who enter are returned to their last safe position, including while mounted (the vehicle is moved back too, although the player may be dismounted).
  - **Hunger Mode on:** entry is allowed but costs health directly - one hit on entry, then `hungerModeFlightDamagePerSecond` every second while they stay. The usual hunger phase is skipped for the void, and exempt players are spared.

`voidFallTicks` (Fabric only) is how many consecutive falling ticks below the world are tolerated before the player is returned.

## Platform differences
Most tuning keys exist on both platforms. The `/antifly set` list always reflects what the running server actually supports.

**Paper only:** `hoverHorizontal` · `elytraToggleGraceTicks` · `elytraStallHorizontalMax` · `elytraStallVerticalMax` · `elytraNoRocketWindowTicks` · `elytraNoRocketMinDescent` · `elytraDurabilityCheckEnabled` · `elytraDurabilityBaseWindowTicks` · `elytraDurabilityUnbreakingMultiplier` · `elytraDurabilitySuspicionLimit` · `elytraRequireMovementSuspicionForDurabilityPunish`

**Fabric only:** `voidFallTicks`

Features that only exist on some server flavours are probed at runtime and fall back to plain Bukkit, so one Paper jar covers the whole 1.21.x - 26.3 range:

- region schedulers (Paper/Purpur/Folia) with a Bukkit scheduler fallback
- Folia entity scheduling, so Hunger Mode health changes run on the thread that owns the player
- `Entity#isInLava()` with a block probe fallback
- the Adventure action bar (Paper family); servers without it simply show no debug action bar

## World toggles and version check
- Disable AntiFly per world with `disabledWorlds: []` on either platform, or live with `/antifly disabledworlds <worldName> <true|false>`.
- Modrinth check (slug `antiflight`): `/antifly status` reports whether you are behind, and the plugin warns on startup when outdated.

## Permissions
Paper only:
- `antifly.admin` (default: op) - manage AntiFly and use `/antifly`
- `antifly.alerts` (default: op) - receive in-game violation alerts
