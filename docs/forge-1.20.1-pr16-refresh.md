# Forge 1.20.1 upstream refresh and performance comparison

Comparison date: 2026-10-29. The product baseline is ordinary upstream `1.20.1`
at `084ae420d4b76c433c717bb5ac768970231d2976`; the previous PR is
`e66742d78d2091ba82d69b8cb355479bab554236`. The resulting version follows
upstream's `2.0.3-beta4`. GTL/GTO branches were not used as baselines.

## Decisions

The combined implementation retains the upstream primitive path for small exact
storage requests and the PR's capacity and provider ownership fixes. Neither
original revision wins every scenario. The measurements support these specific
choices; they do not define an aggregate game-performance score.

| Area | Result and reason |
| --- | --- |
| Optional registration | Keep loaded-mod predicates and lazy factories; include upstream Useless under its actual `useless_mod` ID. |
| NeoECO reflection | Validate public types, static/instance methods, return types and complete abstract callback signatures. Validate admission at the boundary and retain its quantity once. Preserve default callbacks, exact and parallel protocols, and rollback/uncertain ownership distinctions. |
| NeoECO dispatch | Keep owned input copies, reduced-offer re-preparation, captured job identity and single-use submissions. Resolve dynamic parallel providers without a cross-tick capability cache. |
| Byte accounting | Keep the PR's partial-byte, type-limit and 63-bit capacity subtraction fixes, together with separate amount/metadata carry and borrow. The upstream combined-delta variant showed no consistent transfer-path advantage; it is not included. |
| Confirmation menu | Preserve upstream long/exact request lifecycle and the PR's additional optional calculation entry point. Call the wrapped calculation chain so other integrations still participate. |

The upstream segmented undo trail, exact-chain eligibility shortcut and primitive
small exact storage path remain present. The merge also adapts the new upstream
storage tests to the PR's restoring registry fixture.

## Measurements

Both final harnesses ran against each isolated product revision. Only test
harness classes were overlaid; baseline product classes were unchanged. Each
variant used three independent JVMs, with rotated sequential execution order,
Temurin 17.0.20+8, G1, a fixed 512 MiB heap and two active processors on an
i5-13400 / Windows machine. Each scenario warmed for at least one second and
20 batches, followed by 20 measurement batches. The table gives the median of
the three per-JVM p50 values and median main-thread bytes/op. A transfer operation
includes insertion, extraction, checks and consumption of its intermediate state.

All benchmark actions enforce their material, admission or capacity invariants.
The NeoECO benchmark uses a public-contract fixture rather than a physical machine
or NeoECO's material planner. The standard capacity benchmark uses aligned ordinary
amounts; boundary correctness is established separately by the 2,000-case exact
oracle and carry/borrow tests. In particular, a faster incorrect boundary answer
is not treated as an optimization.

| Scenario | Upstream ns/op | Previous PR ns/op | Merged ns/op | Bytes/op (upstream / PR / merged) |
| --- | ---: | ---: | ---: | --- |
| alternatives-1 | 16,904.0 | 64,886.0 | 16,460.0 | 18,683.7 / 18,815.5 / 18,723.7 |
| alternatives-1000000000000 | 12,804.0 | 55,908.0 | 12,618.0 | 18,920.0 / 19,628.2 / 19,471.0 |
| byte-capacity-existing | 6.4 | 12.8 | 6.7 | 0.0 / 0.0 / 0.0 |
| byte-existing-key | 7.2 | 13.7 | 7.2 | 0.0 / 0.0 / 0.0 |
| byte-new-key | 20.6 | 23.1 | 20.8 | 32.0 / 32.0 / 32.0 |
| chain-500 | 485,340.0 | 1,007,830.0 | 508,310.0 | 421,832.0 / 437,864.0 / 437,864.0 |
| indexed-long-transfer | 13.0 | 29.4 | 11.7 | 0.0 / 0.0 / 0.0 |
| indexed-small-exact-transfer | 15.9 | 419.3 | 15.8 | 0.0 / 1,056.0 / 0.0 |
| indexed-wide-exact-transfer | 365.8 | 624.0 | 369.0 | 1,032.0 / 1,232.0 / 1,032.0 |
| neoeco-allocated-offer-3 | 588.9 | 622.3 | 346.5 | 3,728.0 / 3,744.0 / 3,712.0 |
| neoeco-allocated-offer-8 | 252.4 | 436.6 | 224.0 | 2,536.0 / 2,560.0 / 2,520.0 |
| ordinary-bulk-extract | 2,290.6 | 5,288.5 | 2,022.5 | 3,912.0 / 4,088.0 / 3,952.0 |
| quantity-session-1 | 88,850.0 | 391,000.0 | 87,600.0 | 119,948.0 / 123,269.6 / 120,444.8 |
| quantity-session-1000000000000 | 38,500.0 | 295,000.0 | 52,760.0 | 123,625.6 / 129,991.2 / 124,321.6 |
| three-route-mixed | 264,156.0 | 532,960.0 | 223,314.0 | 219,639.0 / 176,632.0 / 176,528.0 |

Small exact transfers reduce the previous PR's 1,056 bytes/op to zero. The
three-route witness retains exactly three firings and 9 units of each raw material,
with about 19.6% less allocation than upstream. Upstream remains better in some
cases: the merged chain scenario allocates 437,864 rather than 421,832 bytes/op,
and its trillion-scale quantity-session median is 52,760 rather than 38,500 ns/op.
Those regressions are recorded, not hidden by a weighted aggregate.

There is substantial timing variation between JVM forks. The additional
combined-delta ByteTracker candidate overlaps the retained implementation in
transfer timings and has the same allocations, so it was not selected on the
basis of a single faster sample. Earlier exploratory samples with insufficient
warmup or an eliminable mutation loop are excluded from this report.

[Per-fork results, ranges, VM configuration and source SHA256](benchmarks/pr16-refresh-20261029.json)
are included without local paths or development logs. No FPS, TPS, full-server
throughput or universal speedup is inferred from these microbenchmarks.
Source hashes use UTF-8 text with LF line endings for cross-platform verification.

To reproduce, use the same final harness checkout for each product checkout:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 `
  -I <final-checkout>/tools/optimization-comparison.gradle `
  -PcomparisonHarnessDir=<final-checkout> `
  -PoptimizationBenchmarkLabel=<variant-and-round> `
  comparePlanningBenchmark compareIntegrationBenchmark
```

Repeat with three rotated rounds across the two exact refs and this merged tree.
Use Java 17 and the same dependencies. The initializer builds only a separate
benchmark source set; it does not replace product sources or enter release JARs.
The current checkout also exposes `optimizationBenchmark` and
`integrationHotPathBenchmark` directly.

## Validation and limits

The new source is checked on Java 17 / Minecraft 1.20.1 / Forge 47.1.3 /
AE2 15.4.10. The historical beta3 combined acceptance is documented separately
and is not substituted for this revision's checks.

| Check | Result |
| --- | --- |
| JUnit | 1,699 total: 1,680 passed, 19 optional skips, zero failures/errors |
| Cold JVM checks | 1 CP-SAT, 20 material replay and 2 replenishment checks passed |
| Required Core GameTest | 14/14 passed, with Mixin audit enabled |
| Crafting Tree composition GameTest | 17/17 passed: the same 14 Core tests plus 3 summary composition tests |
| Real NeoECO facade protocol | 5/5 passed with the cached mapped 20.5.1 source-build artifact |
| Release contract | 14/14 passed |
| Comparison initializer | Both benchmark entry points compiled and completed with their assertions |
| Packaging | Full/slim assembly, both remaps and `verifyReleaseJar` passed |
| Release archive audit | No duplicate paths or test fixtures; one refmap, source-matching Mixin config, license and Java 17 class files |

Frozen validation artifact SHA256 values:

- Full: `a7cfe3b541566c207bd52cd2d8f88c8a62d1a48dc1803c88667330b2592157e5`
- Slim: `60edfd1b3244cce27b9c297484d1ff5c0361336288d34de9e73764234309fa52`

The standard optional skips comprise 12 native NeoECO protocol cases, five modern
facade cases, one EAEP SuperMatrix case and one Useless installed-API case. The
five facade cases were subsequently exercised in their own task against the real
20.5.1 artifact (SHA256
`42dcd76a093dd4ed1cfa9321df69d9496a766e0aaad9c0ea8b1071b373088bb2`).
That task uses proxy providers and real public facade/planner/transaction classes;
it does not construct a physical NeoECO machine. Native contract fixtures remain
covered by the standard suite. The cached newer EAEP artifact that exposes the
optional SuperMatrix interface has Java 21 class files, so it does not establish
Java 17 runtime compatibility for that path.

The new real-menu GameTests verify full long amounts on replan, reset when a
native request replaces an extended request, algorithm tracking cleanup on
replacement/close, and rejection of an unavailable exact backend without
cancelling the current job. Core exposes exact backend extension APIs; no built-in
backend is registered here, and successful arbitrary-width CPU execution is not
claimed. Client rendering, independent-client multiplayer and long-duration server
stress have not been repeated on this revision.
