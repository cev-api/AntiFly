# AntiFly

![LOGO](https://i.imgur.com/WQqocWS.png)

**Flight restriction that does one job properly.** Lightweight Bukkit + Fabric plugin/mod by [CevAPI](https://cevapi.dev).

Current release: `1.1.4`

AntiFly is not a full anti-cheat, and that is deliberate. It is built for servers that want to restrict **flight and nothing else** - no reach checks, no killaura heuristics, no combat module quietly flagging your players at 3am. What you get instead is flight control accurate enough to leave legitimate movement alone.

## Why AntiFly

- **It never trusts the client.** Ground and support are verified server-side. A client claiming `onGround` cannot talk its way past a check.
- **Layered detection, not one threshold.** Buffered air, hover and anti-kick suspicion sit alongside a sustained-air backstop, so slow level flight and gentle bobbing are caught - not just obvious speed hacks.
- **Setbacks nobody complains about.** Players are corrected to their last known valid support instead of being yanked out of the sky mid-air.
- **Elytra handled properly.** A trajectory verifier predicts the next vanilla glide step from pitch, yaw and prior velocity, then weighs residual motion, excess energy, unnaturally constant speed and impossible turns - and only acts when several channels agree. Rocket boosts, steep dives, pull-ups and obstacle crashes are all left alone.
- **Teleport-aware.** `/tp`, ender pearls, chorus fruit and portals are rebased rather than mistaken for flight, so legitimate travel never rubber-bands.
- **It does not advertise itself.** The root command is permission-gated on both platforms so clients that probe a server's command list with suggestion packets get nothing back. 
- **One shared codebase.** The same checks run on Paper and Fabric, so a detection is never fixed on one platform and forgotten on the other.

## Hunger Mode

Hard blocks are not always what a survival server wants. `/antifly hungermode on` replaces them with a penalty that fits the game:

- **Food drains first**, scaling with speed and never falling below a minimum while airborne - so an idle hoverer still pays.
- **Then health**, once the flight has lasted long enough (or the player is already starving). Damage ticks slowly, so a landing attempt is always survivable.
- **Vanilla gliding and rockets stay free** unless the movement shows genuine exploit evidence.

The upshot: no rubber-banding, no teleport-loop complaints, and an economy that discourages flight naturally instead of a wall that just frustrates players.

## Void access - new in 1.1.4

A bonus toggle for servers that want to close off the void as well. `/antifly voidaccess off` covers the **Nether roof (Y ≥ 128)**, the **void below the Nether** and the **void below the Overworld**.

- With Hunger Mode off, players who enter are returned to their last safe position, including while mounted.
- With Hunger Mode on, entry is allowed but costs health directly - one hit on entry, then once per second while they stay. The usual hunger phase is skipped here, and exempt players are spared.

## Platforms
- Bukkit family: 1.21.x - 26.3 (Bukkit, Spigot, Paper, Purpur, Folia)
- Fabric: 1.21.11 / 26.1.2 / 26.2 / 26.3

## Build
```bash
./gradlew build
```
Artifacts land in `paper/build/libs/` and each `fabric_*/build/libs/`.

All four Fabric targets compile one shared source tree (`fabric_shared/`). Only the Minecraft, loader and Fabric API versions in each `fabric_*/build.gradle` differ, so there is no per-version Java code to keep in sync - a change to the checks is automatically in every Fabric jar.

## Server compatibility
The Paper build is compiled once against the oldest supported API (1.21.1) so a single jar runs on every Bukkit-family server from 1.21.x up to 26.3. Every build re-verifies that the sources still link against the oldest Paper API, the newest Paper API, and plain Spigot/Bukkit:
```bash
./gradlew :paper:checkPaperApiCompatibility
```
Anything that only exists on some servers is probed at runtime and falls back to plain Bukkit:
- region schedulers (Paper/Purpur/Folia) with a Bukkit scheduler fallback
- Folia entity scheduling so Hunger Mode health changes run on the thread that owns the player
- `Entity#isInLava()` with a block probe fallback
- the Adventure action bar (Paper family); servers without it simply show no debug action bar

## Install
- Bukkit family: drop the jar into `plugins/`
- Fabric: drop the jar into `mods/`

## Commands
The same set on Paper and Fabric. Requires op or `antifly.admin` on Paper, moderator-level permissions on Fabric.

- `/antifly enable|disable|status|help`
- `/antifly hungermode <on|off>` - swap hard blocks for the hunger and health penalty
- `/antifly voidaccess <on|off>` - allow or block the void (default on)
- `/antifly alerts <off|game|console|both>` - where violation alerts are delivered
- `/antifly reload` - re-read the config from disk, no restart needed
- `/antifly set` - list every tunable key with its current value

Also available: `/antifly set <key> [value]`, `/antifly debug`, `/antifly exempt` / `unexempt`, `/antifly reset` and `/antifly disabledworlds`. The full command reference is in **[CONFIGURATION.md](CONFIGURATION.md)**.

## Configuration
Every check is tunable at runtime with `/antifly set <key> <value>`. Changes apply instantly and auto-save, so there is no restart and no file editing.

Defaults are chosen to stay forgiving of vanilla movement - sprinting, Depth Strider, Dolphin's Grace, Speed potions, wind charges, knockback and rockets are all expected and unpunished - so the common case really is "install it and leave it alone".

- Paper / Bukkit: `plugins/AntiFly/config.yml`
- Fabric: `config/antifly.json`

**[CONFIGURATION.md](CONFIGURATION.md)** documents every key and its default, the full command reference, the Elytra trajectory verifier in depth, Hunger Mode and void tuning, and which keys are platform-specific.

## Permissions (Paper)
- `antifly.admin` (default: op) · `antifly.alerts` (default: op)

## License
GNU General Public License v3.0 or later (GPL-3.0-or-later) - see [LICENSE.txt](LICENSE.txt).
