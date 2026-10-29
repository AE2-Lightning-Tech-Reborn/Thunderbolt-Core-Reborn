# Forge 1.20.1 compatibility and validation

This is the historical **beta3** acceptance record. The subsequent merge on
`084ae42` uses beta4 and requires its own results; see
[the upstream refresh and performance report](forge-1.20.1-pr16-refresh.md).

This update consolidates the verified Core 2.0.3-beta3 changes for Minecraft 1.20.1,
Java 17 and AE2 15.4.10.

## Behavior changes

- Preserve exact crafting amounts and reusable seed metadata in plan summaries;
  keep third-party crafting tree summaries and optional integrations compatible.
- Bound wide input enumeration and feedback analysis, reuse immutable planner
  choices, and certify batch-aware replenishment against the complete material
  graph without increasing planning deadlines or work budgets.
- Preserve cooperative cancellation when interruption arrives concurrently with
  a planning checkpoint.
- Correct storage byte carry/borrow and type overhead boundaries, preserve indexed
  storage identities and restored contents, and keep eject registrations distinct
  across hosts and replacement handles.
- Support native and allocated NeoECO dispatch with explicit job ownership,
  partial acceptance, refund and uncertain-acceptance handling. Validate reflected
  API shapes, support interface default methods, copy borrowed inputs and reject
  missing job context safely.
- Keep compatibility fixtures outside release packaging. The optional summary
  GameTest initializer is provided in
  `tools/summary-compatibility-game-tests.gradle`.

The ordinary main reference
[8301d7f](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/8301d7fcf03ffcc76b26007e6ef7a0fff7cfa0e3)
was adapted to the Forge APIs. Existing licenses, source headers and attribution
are retained.

## Validation

The complete Core suite used Forge 47.1.3 / AE2 15.4.10 / Java 17.

| Check | Result |
| --- | --- |
| Standard JUnit | 1,606 total: 1,588 passed, 18 optional skips, no failures or errors |
| Existing cold JVM tasks | 1 CP-SAT, 20 material replay and 2 replenishment checks passed |
| Required Core GameTest | 12/12 passed |
| Release contract | 14/14 passed |
| Build and packaging | build, jarJar, reobfJar, reobfJarJar and verifyReleaseJar passed |

The optional skips are 12 native NeoECO protocol cases, 5 allocated facade cases
and 1 EAEP SuperMatrix case. The required released interfaces were not supplied
to this run. Contract fixtures do not establish real machine compatibility for
those absent interfaces.

The verified artifacts have these SHA256 values:

- Full: `fc093e16f2e8f29db70967ce62db5d0b9cc46e3b8095151088f4f31294ccafe3`
- Slim: `96450b89fa562725d46744cf5d5749c4f3129ece2386978e9ec15f88d8983ba3`

Joint acceptance with AE2LT 2.1.2-beta2 and PP 1.2.2-beta covered development-mapped
clients, two independent clients connected to a dedicated server, and unchanged
production JAR dedicated-server cold starts, saves and restarts. The tested
NeoECO/LDLib pair was 20.3.0 / 1.0.52.a. Production JAR client execution and
long-duration public-server stress were not performed.

The original PR source matched the validated beta3 product and regression-test sources.
The verified source and artifact hashes were checked again before submission;
the expensive game and Gradle suites were not repeated for unchanged sources.
