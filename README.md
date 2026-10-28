# Thunderbolt Core Reborn

[简体中文](README_zh_CN.md)

Thunderbolt Core Reborn is the shared AE2 optimization and infrastructure layer for
AE2 Lightning Tech Reborn. It can also be installed as a standalone AE2 autocrafting
accelerator.

This is the **Minecraft Forge 1.20.1 GTO** branch. For Minecraft 1.21.1 and
NeoForge, see the [`main`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/tree/main)
branch.

## Requirements

- Minecraft `1.20.1`
- Forge `47.4.20` or newer (not NeoForge)
- Java `21`
- GTOCore `26.10.9` or newer, matching NeoECO `1.20.1-GTO`'s minimum
- AE2-GTO `15.2610.31` or newer, below `16`; compile baseline `15.2610.31`

Starting with `2.0.3-gto.2`, the declared requirements are GTOCore `[26.10.9,)`
and AE2 `[15.2610.31,16)`, matching NeoECO's GTO `0.6.0.23` target environment.
These loading constraints do not establish runtime compatibility for every
accepted combination; use an LT build for the same environment. Existing `gto.1`
JARs retain their original requirements.
See the [GTO compatibility notes](docs/GTO_COMPATIBILITY.md).

Install Thunderbolt Core Reborn in the `mods` directory on both the client and
server using this dependency combination, retaining the modpack's bundled AE2.

Optional NeoECO support targets [`20.5.0-gto.1`](https://github.com/Yang120231/NeoECOAEExtension/tree/1.20.1-GTO).
Upgrade the GTO baseline together. Existing AE2LT `2.1.2-gto.1` and AppliedE
`0.14.3-gto` artifacts reject these newer dependencies. See the
[compatibility and validation record](docs/GTO_NEOECO_GTO_20261026.zh-CN.md).

## Features

- faster AE2 autocrafting planning with ordered planner selection and native
  AE2 fallback
- batch dispatch, closed-loop crafting, time-wheel scheduling, and overloaded
  pattern support
- extension APIs for crafting providers, high-capacity channels, indexed
  storage cells, and eject endpoints
- max-flow channel allocation for compatible high-capacity networks
- optional compatibility hooks for Advanced AE, AE2 Crafting Tree,
  ExtendedAE Plus and NeoECO GTO; hooks load only when the corresponding mod is present

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

- Version: `2.0.3-gto.2`
- Maven coordinate:
  `com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1:2.0.3-gto.2`
- Distributable JAR:
  `build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.2.jar`

The `-slim.jar` artifact does not contain the required MixinExtras jar-in-jar
dependency and is only an intermediate development artifact.

Issues: [GitHub Issues](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/issues) ·
License: [GNU LGPL 3.0](LICENSE)
