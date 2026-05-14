# AntiFly

![LOGO](https://i.imgur.com/WQqocWS.png)

Lightweight Paper + Fabric **flight-control plugin** by CevAPI.

AntiFly is **not a full anti-cheat**.  
It exists for one purpose only: **to block flight and extreme movement exploits**.

Only the following are checked:
- Unauthorized flight
- Excessive movement speed (ground, air, water)
- Elytra speed, stall, and slowdown behavior (optional)
- Vehicle flight while preserving normal mount behavior, downhill movement, and genuine falling

Nothing else is monitored or restricted.  
No combat checks. No scaffolding checks. No packet analysis. No behavior profiling.

Designed specifically for **anarchy and semi-anarchy servers** that want to stop flight hacks without policing gameplay.

## Platforms
- Paper: 1.21.x
- Fabric: 1.21.11 (mojmap, requires Fabric Loader 0.18.1 and Fabric API 0.141.1+1.21.11)

## Build
```bash
./gradlew build
```

Artifacts:
- Paper: `paper/build/libs/AntiFly-paper-1.x.x.jar`
- Fabric: `fabric/build/libs/AntiFly-fabric-1.x.x.jar`

## Install
Paper:
- Drop the Paper jar into `plugins/`

Fabric:
- Drop the Fabric jar into `mods/`

## Ops Commands
These commands are available in-game. Requires op/admin.

- Paper: requires op or `antifly.admin`
- Fabric: requires moderator-level command permission
- Non-ops/non-admins cannot use or tab-complete AntiFly commands.

```
/antifly enable
/antifly disable
/antifly status
/antifly help
/antifly exempt <player>
/antifly unexempt <player>
/antifly set airSpeed <value>
/antifly set airVertical <value>
/antifly set airNonFallTicks <value>
/antifly set antiKickWindowTicks <value>
/antifly set antiKickMinDescent <value>
/antifly set waterSpeed <value>
/antifly set waterVertical <value>
/antifly set groundSpeedWalking <value>
/antifly set groundSpeedMounted <value>
/antifly set vehicleFallMinDescent <value>
/antifly set vehicleFallMaxHorizontal <value>
/antifly set vehicleFallTicksMax <value>
/antifly set elytraEnabled <value>
/antifly set elytraMaxHorizontal <value>
/antifly set elytraMaxUp <value>
/antifly set elytraMaxDown <value>
/antifly set elytraStallHorizontalMax <value>
/antifly set elytraStallVerticalMax <value>
/antifly set elytraStallTicks <value>
/antifly set elytraSlowdownMinSpeed <value>
/antifly set elytraSlowdownMinScale <value>
/antifly set elytraSlowdownGraceTicks <value>
/antifly reset <player>
```

Notes:
- `/antifly set` shows all current settings.
- `/antifly set <key>` shows the current value for that key.
- `/antifly unexempt <tab>` suggests only currently exempt players.
- Default ground limits:
  - `groundSpeedWalking=0.49`
  - `groundSpeedMounted=0.750`
- Vehicle fall tuning defaults:
  - `vehicleFallMinDescent=-0.04`
  - `vehicleFallMaxHorizontal=0.40`
  - `vehicleFallTicksMax=60`
- Legacy `groundSpeed` remains accepted as an alias of `groundSpeedWalking`.
- Fence/wall top collisions are treated as valid ground support.
- Horse and other mount jumps have extra vehicle-air grace before flight is blocked.

## Modrinth Version Check
AntiFly can compare the running version against Modrinth project `antiflight` and alert ops/admins if the server is outdated.

- API used: `https://api.modrinth.com/v2/project/<slug>/version?featured=true&include_changelog=false`
- `/antifly status` performs a live check.
- A startup check also runs and warns ops/admins when outdated.

## Config
- Paper: `plugins/AntiFly/config.yml`
- Fabric: `config/antifly.json`
