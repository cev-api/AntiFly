# AntiFly
Lightweight Paper + Fabric **flight-control plugin** by CevAPI.

AntiFly is **not a full anti-cheat**.  
It exists for one purpose only: **to block flight and extreme movement exploits**.

Only the following are checked:
- Unauthorized flight
- Excessive movement speed (ground, air, water)
- Elytra speed, stall, and slowdown behavior (optional)

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
These commands are available in-game. Requires op or `antifly.admin` on Paper.

```
/antifly enable
/antifly disable
/antifly status
/antifly help
/antifly exempt <player>
/antifly unexempt <player>
/antifly set airSpeed <value>
/antifly set airVertical <value>
/antifly set waterSpeed <value>
/antifly set waterVertical <value>
/antifly set groundSpeed <value>
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

## Config
- Paper: `plugins/AntiFly/config.yml`
- Fabric: `config/antifly.json`

