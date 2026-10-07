# Thunderbolt Core Reborn

[简体中文](README_zh_CN.md)

Thunderbolt Core Reborn is the shared AE2 optimization and infrastructure layer for
AE2 Lightning Tech Reborn. It can also be installed as a standalone AE2 autocrafting
accelerator.

This is the **Minecraft Forge 1.20.1_GTL** branch. For Minecraft 1.21.1 and
NeoForge, see the [`main`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/tree/main)
branch.

## Requirements

- Minecraft `1.20.1`
- Forge `47.1.3` or newer (not NeoForge)
- Java `17`
- Applied Energistics 2 `15.4.10`–`15.x`

Install Thunderbolt Core Reborn and AE2 in the `mods` directory on both the client and
server.

## Features

- faster AE2 autocrafting planning with ordered planner selection and native
  AE2 fallback
- batch dispatch, closed-loop crafting, time-wheel scheduling, and overloaded
  pattern support
- exact BigInteger storage, certified execution programs, and oversized
  crafting previews
- extension APIs for crafting providers, high-capacity channels, indexed
  storage cells, and eject endpoints
- max-flow channel allocation for compatible high-capacity networks,
  using a bidirectional tree seed and exact residual completion
- optional compatibility hooks for Advanced AE, NeoECO, AE2 Crafting Tree,
  and ExtendedAE Plus; hooks load only when the corresponding mod is present
- coexistence with GTLCore (`gtlcore`): Thunderbolt yields CPU dispatch to
  GTLCore, attaches the planner at `CraftingCalculation#computePlan`,
  and keeps its other subsystems active

The hand-over boundary, the `-Dthunderbolt.gtlCompat` switch and the
verification steps are described in `docs/gtl-core-coexistence.zh-CN.md`.

## Configuration

V2 improves feasible plans by preferring fewer recipe executions and may use different
available inventory to shorten a route. At equal execution counts, it only accepts
material savings without increasing another material's draw. This optimization is bounded
by the planning budget; it does not guarantee a globally optimal plan.

Common options are written to `config/thunderbolt-common.toml`:

- `planning.enableCpSatPlanner`: enables the experimental OR-Tools CP-SAT
  planner (default: `false`). When enabled, Thunderbolt downloads and verifies
  the matching native runtime at startup. If loading fails, the other planners
  remain available.
- `channel.mode`: controls max-flow channel allocation (default: `MOD`). `MOD`
  enables it when a loaded integration requests it, `DEVICE` also enables it
  for an opted-in device, and `ON` enables it whenever a controller is present.

Advanced planner diagnostics and safety limits can be set as JVM system
properties:

- `-Dthunderbolt.planningWarnMs=<ms>`: slow-planning warning delay (default:
  `2000`; legacy alias: `thunderbolt.watchdogMs`)
- `-Dthunderbolt.planningTimeoutMs=<ms>`: cooperative exit deadline (default:
  `3000`)
- `-Dthunderbolt.planningInterruptGraceMs=<ms>`: grace period before interrupt
  (default: `2000`)
- `-Dthunderbolt.planningStopGraceMs=<ms>`: total post-deadline grace before
  isolation (default: `5000`)
- `-Dthunderbolt.maxCraftSearchWork=<count>`: planner search-work budget (default:
  four times `thunderbolt.maxReachablePlanningWork`, or `262144`)
- `-Dthunderbolt.maxCraftDepth=<count>`: planner depth limit
- `-Dthunderbolt.feasibleOptimizationStallMs=<ms>`: stop optional optimization
  after this long without a 1% execution-count gain (default: `500`; `0` disables
  the stall cutoff, not the enclosing deadline)
- `-Dthunderbolt.maxConsumptionOptimizationNanos=<ns>`: total optional optimization
  allowance per calculation (default: `2800000000`; minimum: `1000000`). The
  enclosing deadline and shared work/probe budgets can stop it earlier.

## Acknowledgements

Thanks to **AdUhTkJm**, author of [AW](https://github.com/AdUhTkJm/AW), for the
optimization research and comparison cases that informed our work on reducing recipe
executions. Thunderbolt's bounded proposals retain its own inventory and execution checks.

## Development

The [AWR benchmark adapter](scripts/awr-benchmark/README.md) supports v1–v3 datasets,
weighted multi-output recipes and explicit zero-cost tag transfers on Java 17.

Build the distributable JAR:

```powershell
.\gradlew.bat build
```

Publish it to the local Maven repository:

```powershell
.\gradlew.bat publishToMavenLocal
```

- Version: `2.0.0`
- Maven coordinate:
  `com.moakiee.thunderbolt:thunderbolt-forge-1.20.1:2.0.0`
- Distributable JAR:
  `build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`

The `-slim.jar` artifact does not contain the required MixinExtras jar-in-jar
dependency and is only an intermediate development artifact.

Issues: [GitHub Issues](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/issues) ·
License: [GNU LGPL 3.0](LICENSE)
