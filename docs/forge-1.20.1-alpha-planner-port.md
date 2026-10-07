# Alpha planner backport to Forge 1.20.1

Source: `alpha` at `0297f2d`; destination baseline: `1.20.1` at `2fcb92e`.
Changes are grouped by functionality and retain the existing Java 17, Forge 47.1.3
and AE2 15.4.10 platform adaptations.

## Producibility-ranked cycle orientation

Port the cycle-orientation portion of `948c4c1`:

- Rank reachable recipe inputs by producibility and retain a producible direction
  inside each strongly connected component when ordinary cycle cuts fail.
- Try both stock-seeded and stock-free rankings within the shared search budget.
- Scale the default search-work budget with the reachable-graph limit so increasing
  that limit also permits the necessary orientation retries.
- Include the upstream regression graph whose valid route was lost by DFS cuts.

The Forge-specific replenishment certificates, Java 17 verification allowances,
bootstrap-reserve reuse and optional optimization guard remain intact in this batch.

Validation: Java 17 / Forge 47.1.3 / AE2 15.4.10 planner suite,
679 tests passed with no failures or skips.

## Execution-first feasible-plan optimization

Port the final optimizer from `948c4c1` and `17d1ee1` together:

- Prefer fewer machine executions, using stock consumption as the tie-breaker.
  Never increase any incumbent stock draw or change stateful recipe firings.
- Use forward producibility constraints and execution/stock lower bounds to prune
  unhelpful candidates before invoking the planner.
- Propose cheap whole-graph policies, then reroute busy recipes in local regions;
  widen regions and chain substitutes when necessary.
- Charge each probe for its actual reachable region, within the existing shared
  search budget. Preserve completed executable plans on optional exhaustion and
  propagate external cancellation.
- Stop stalled optional search after 500 ms without a 1% execution gain, within
  the existing 2.8-second allowance and enclosing planning deadline.

The former Forge-only `maxConsumptionOptimizationWork` gate and graph-size probe
caps are removed, as requested. A graph with 6,000 unfunded alternatives now
verifies that useful local optimization still runs above the former 16,384-work
gate with only 256 units of search budget. Insufficient budget, stock preservation,
independent material balance, cancellation and incumbent retention are covered.

## Scope and API audit

- The global batch-provider adapters from `7559206` already exist in destination
  baseline `2fcb92e`; they are not reimported.
- Not applicable to official Useless 1.20.1: the Useless/Omni Alloy Furnace
  batch-throttle correction (`0297f2d`) requires the 1.21.1 BigInteger API.
  The inactive Useless bridges and their dedicated tests were removed after the
  2026-10-04 source audit; see `global-batch-provider-adapters.zh-CN.md`.
- Not applicable: `9c95dd4` makes a client injection optional because AE2 19.2.18
  removed `CPUSelectionList.formatStorage`. The actual AE2 15.4.10 sources still
  define that method with the expected descriptor, so its required injection is
  retained. The older branch already hooks `Tooltips.getByteAmount` as well.
- Retain AE2 15's array-based pattern API, Java 17 collections and threads, Forge
  registrations and Mixins, legacy NBT/packet codecs, and the destination's exact
  storage recovery fixes. Neither planner batch imports NeoForge APIs or Java 21
  requirements.

## Final validation

- `gradlew.bat test build` under Java 17, Forge 47.1.3 and AE2 15.4.10:
  897 tests, 896 passed, no failures/errors, one optional ExtendedAE Plus runtime
  probe skipped. Forge remapping and distributable JAR assembly succeeded.
- The final optimizer source matches `alpha` exactly. The remaining differences
  in `CraftPlannerV2` are the retained Forge replenishment/Java 17 allowances and
  bootstrap-reserve reuse.
- Verified Java 17 class version, Forge mod metadata, Mixin configuration/refmap,
  embedded MixinExtras and license in the distributable JAR.
- Optional-mod adapters, Mixins and storage code are unchanged from `2fcb92e`.
- Validation here covers unit/regression tests and the Forge build; no interactive
  client or server gameplay session was launched for this backport.

Artifact: `build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`.

## Stock-aware and bounded planning backport (2026-10-04)

This section records upstream backport validation, not this workspace's later
integration. For retained local fixes and the subsequent Forge stress/GameTest
results, see [the local integration report](stock-aware-upstream-update-20261004.zh-CN.md).

Source: `0a7829218e0e4a8679b4677bfd57f679144cf84f`;
destination baseline: `28a1f25` on `1.20.1`.

- Port inventory-aware execution reduction, bounded ordinary pair/triple and
  upstream batch search, terminal-route optimization, preprocessing reuse and
  material-footprint extraction. Port the cancellation and shared recovery-budget
  reporting fixes together with their regression tests.
- Preserve the destination's calculation-scoped `IndexCache`. The optimizer now
  receives both that cache and the shared work callback, so cache reuse cannot
  reset or bypass mixed-batch work/probe limits. A regression verifies budget
  rejection followed by repeated cached searches with fresh work accounting.
- Replace Java 21 `List.getFirst()` calls with Java 17 `List.get(0)` in production
  and test sources. The new components depend only on Java and the planner core;
  no NeoForge or AE2 19 APIs are imported.
- Preserve Forge-specific direct replenishment certification, Java 17 optional
  verification allowances and bootstrap-reserve scratch reuse. Forge 47.1.3,
  AE2 15.4.10, Java 17, Forge remapping, Mixin refmaps and JarJar packaging remain
  the destination platform baseline.
- Retain all four destination optimizer regressions in addition to the source
  commit's tests. Adapt the two reflective target-bound tests to the constructor
  that also accepts the retained cache.

The imported `planner-alpha-*` and structural benchmark records describe the
original Java 21 alpha validation. Their timing improvements are historical source
evidence, not a Forge 1.20.1 performance measurement.

Validation of this backport:

- `gradlew.bat test build --console=plain` with Temurin 17.0.19+10:
  1,089 tests, 1,088 passed, no failures/errors, one optional ExtendedAE Plus
  runtime probe skipped. Forge remapping and `verifyReleaseJar` succeeded.
- The distributable contains 472 production classes, all Java 17 (major 61),
  with the same class names as the tested compiler output and all 92 classes
  belonging to the 15 ported planner components. Obsolete nested material
  footprint classes are absent.
- Forge metadata, Mixin configuration, generated refmap, embedded MixinExtras and
  license were verified. Forge reobfuscation transforms bytecode, so this audit
  does not claim byte identity with pre-remapping compiler output.
- No interactive client/server session or Forge performance benchmark was run.

Artifact and validation provenance:
[`benchmarks/planner-forge-1201-port-20261004.json`](benchmarks/planner-forge-1201-port-20261004.json).

## Fixed-prefix and terminal-portfolio backport (2026-10-04)

Source: `0060538c8acf5edcbb0af10ae3da5ebbf640d7d0`;
destination baseline: `5ce6adf` on `1.20.1`.

- Port calculation-local fixed-prefix reuse and direct single-route batch counts.
  Preserve the source's work charging, execution-limit rejection order and
  cancellation checks; no cached state crosses an incumbent search.
- Port bounded terminal shortage recovery and the supplemental one/two/three-route
  optimizer. Retain the source's scope restrictions, original-graph certificates,
  shared work/probe limits, optional deadlines and incumbent retention.
- Adapt the two new production `List.getFirst()` calls and the two new test
  `List.getFirst()`/`getLast()` calls to Java 17 indexing. The pre-existing Java 17
  collection adaptation remains in place. These components use only Java and
  planner-core APIs; no NeoForge or newer AE2 API is introduced.
- Retain the destination's calculation-scoped `IndexCache`, direct replenishment
  certification, Java 17 verification allowances and bootstrap-reserve scratch
  reuse. A destination-specific integration regression verifies that shared-work
  refusal does not poison repeated cached searches, that the three-route result
  still has an original-graph certificate, and that each invocation charges fresh
  work without exceeding the global probe allowance.

Validation with Temurin 17.0.19+10, Forge 47.1.3 and AE2 15.4.10:

- `gradlew.bat clean test build --console=plain`: 1,146 tests, 1,145 passed,
  zero failures/errors, one optional ExtendedAE Plus runtime probe skipped.
  All 57 tests in the four imported suites, including the new cache integration
  regression, passed. Forge reobfuscation and `verifyReleaseJar` succeeded.
- The distributable contains 483 production classes, all Java 17 (major 61),
  and its production class names match the tested compiler output. Both new
  terminal components and their nested classes are present. Forge metadata,
  Mixin configuration/refmap, embedded MixinExtras and license were verified.
- `SmallConservativeSearch`, `TerminalBatchRecovery` and
  `TerminalFixedDepthOptimizer` match the source commit; `UpstreamBatchOptimizer`
  matches after Java 17 collection adaptation. The other two changed production
  files retain only the pre-existing destination adaptations relative to source.
- Imported round-six/round-seven alpha benchmark records remain Java 21 source
  evidence. Their performance and generated-graph audits were not rerun on Forge
  1.20.1. No interactive client/server gameplay session was launched.

Artifact: `build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`.
Validation provenance:
[`benchmarks/planner-forge-1201-round6-round7-port-20261004.json`](benchmarks/planner-forge-1201-round6-round7-port-20261004.json).

## Bounded-portfolio follow-up backport (2026-10-04)

Source: alpha `2132894`; destination baseline: `6fa3a80` on `1.20.1`.

Most round-eight alpha changes originated in this Forge branch and are already
present. This follow-up ports only the remaining behavioral differences:

- Reuse the existing target-only execution/stock lower-bound certificate before
  the two-to-six-route mixed search, within the optional-stage deadline. A public
  planner regression verifies that a proven optimum skips optional search.
- Give the admitted terminal recovery portfolio a cumulative 100 ms allowance.
  Ordinary small-graph recovery retains 20 ms. Shared work, caller deadline,
  4096-state and 32-execution limits, and route/resource admission remain intact.
- Preserve Forge's `thunderbolt.maxConsumptionOptimizationNanos` override,
  Java 17 collection adaptations, direct replenishment checks, graph-scaled
  verification allowances, topology extraction and platform integrations.

Validation with Java 17, Forge 47.1.3 and AE2 15.4.10:

- Offline `test build` passed: 1191 ordinary tests, 1190 passed, one skipped,
  zero failures/errors; the separate cold CP-SAT refinement test also passed.
  Forge reobfuscation and `verifyReleaseJar` succeeded.
- Two independent runs of 192 locally generated inventory/target queries match
  the preserved `6fa3a80` snapshot in support, feasibility, budget cutoff, stock,
  missing materials and firing vectors. All 124 feasible plans per run passed
  stock/balance checks and execution replay. The other 68 retain their existing
  missing/budget-limited outcomes; this is not an infeasibility proof.
- The release JAR contains 492 production classes, all Java 17 (major 61), with
  names matching the tested compiler output. Forge metadata, Mixin config/refmap
  and JarJar metadata are present. Reobfuscation changes bytecode, so compiled
  classes and release classes are not claimed to be byte-identical.

A separate Java 17 synthetic-control comparison used serial ABBA order, two JVM
forks per endpoint, 500 warmups and 500 measured calls per case per fork. Values
below are medians of per-fork medians; negative change means lower elapsed time.

| Control | Baseline μs | Candidate μs | Wall-time change |
| --- | ---: | ---: | ---: |
| additional-inventory-control | 20.550 | 21.500 | +4.6% |
| already-optimal-control | 18.725 | 15.625 | -16.6% |
| fixed-chain-control | 133.250 | 142.850 | +7.2% |
| shared-quantity-probes | 804.050 | 750.275 | -6.7% |
| three-route-mix | 23.350 | 23.000 | -1.5% |
| two-route-mix | 26.600 | 31.650 | +19.0% |

The already-optimal control allocates 28,496 → 21,176 bytes per call, but the
additional early certificate also adds work when it cannot prove optimality.
The two-route control regresses by about 5.05 μs; all unfavorable controls are
retained. These small synthetic measurements do not establish a general Forge
speedup. No pack timing benchmark, interactive gameplay or server TPS measurement
was performed for this follow-up. Alpha round-eight timings in the Chinese
optimization document remain Java 21 source evidence.

Raw data, local scripts and reports remain untracked under
`build/recipe-benchmark/forge-followup/`; `measurements/summary.json` records input,
source, compiled-class and artifact hashes, both corpus runs and all micro samples.
This query set differs from the historical published 192-query corpus.

Artifact: `build/libs/thunderbolt-forge-1.20.1-2.0.0-beta.5.jar`.
SHA-256: `b9a321b852be569bc0067988784af2cf0f55ed05280c05c9f43f328443c8f3e0`.

## 补料验证预算与排序拓扑复用（2026-10-05）

本节仅记录 Forge 1.20.1 / Java 17 的独立验证，修复前后端点为 `6e0511c` → `3f36b99`。

补料细化已有外层时间限制，内部材料排序不再重复将剩余时间四等分；普通规划保留原时间预留。复用已完成的排序拓扑，每个探针单独刷新库存和供给上界，不共享含旧库存的 `PreparedGraph`。原库存下供给为零的拓扑也保留，因为补料可能启用该路线。可选超时保留已完成的候选，部分拓扑不被当作不可行证明；必要时在相同累计预算内重新编译，并清除按索引缓存的准备数据。用户取消和路由退出继续传播，外层超时保留最后一个可执行补料。

### 验证与测量方法

完整 `test build` 共 1,243 项，1,242 通过、1 跳过、零失败/错误；另通过独立冷启动 CP-SAT 回归、Forge 重混淆和 `verifyReleaseJar`。

- 沿用已有 TBR1 配方投影与固定库存、目标清单，Vanilla、Small、NAST、ATM 各 48 查询，共 192 查询；每端点在独立 JVM 中重复两次。
- 每类计时按修复前、后、后、前、后、前、前、后（ABBA/BAAB）串行启动 8 个 JVM，每端点各 4 个，参数为 `-Xmx3g -Xss16m`。
- 大图使用 random20 库存，NAST 目标为 `mekenergistics:me_supreme_combining_factory`，ATM 为 `pneumaticcraft:kerosene`，请求量均为 100,000；每 JVM 每数据集预热 8 次、采样 8 次。
- 六个小图对照每场景预热 500 次、采样 500 次。补料场景取 `MaterialDagBudgetTest` 的 `sharedCycle` 与 `batchCycle`，目标为 `M6 × 2`，每场景预热 20 次、采样 40 次。
- 大图、小图对照沿用 2,000,000 的 `thunderbolt.maxReachablePlanningWork` 和 `thunderbolt.maxCraftSearchWork`；补料循环使用默认预算。
- 端点基于已验证构建快照，以相同 javac 参数重编译各自提交的 `CraftPlannerV2`、`MaterialDagOrders` 及嵌套类，其余生产类相同。输入、源码和类文件记录哈希，结束后确认输入未变。
- 计时只覆盖规划调用，排除解析、构图和校验。下表先取各 JVM 样本中位数，再取 JVM 间中位数；负值表示减少。

### 初测结果

| 场景 | 修复前 ms | 修复后 ms | 耗时变化 | 分配变化 |
| --- | ---: | ---: | ---: | ---: |
| recipes-atm | 757.2135 | 752.1644 | -0.7% | -0.0% |
| recipes-nast | 447.3172 | 446.6044 | -0.2% | +0.1% |
| additional-inventory-control | 0.0204 | 0.0204 | +0.0% | +0.0% |
| already-optimal-control | 0.0123 | 0.0125 | +1.4% | -0.3% |
| fixed-chain-control | 0.1428 | 0.1463 | +2.5% | -2.3% |
| shared-quantity-probes | 0.6430 | 0.6953 | +8.1% | +0.7% |
| three-route-mix | 0.0222 | 0.0221 | -0.9% | +0.0% |
| two-route-mix | 0.0344 | 0.0348 | +1.3% | +0.0% |
| batch-cycle | 32.0433 | 30.7284 | -4.1% | +5.0% |
| shared-cycle | 34.2395 | 30.6549 | -10.5% | -5.9% |

### 长预热复核

共享数量探测初测变慢，因此对该场景及固定链另跑一组相同端点、参数和 ABBA/BAAB 顺序的对照，每 JVM 每场景预热 10,000 次、采样 4,000 次。两组数据分开统计，保留初测和所有不利结果。

| 场景 | 修复前 ms | 修复后 ms | 耗时变化 | 分配变化 |
| --- | ---: | ---: | ---: | ---: |
| fixed-chain-control | 0.0391 | 0.0407 | +4.1% | -0.1% |
| shared-quantity-probes | 0.5043 | 0.4919 | -2.5% | +0.3% |

初测共享数量探测的退化在长预热下未复现，说明该微基准对预热条件敏感。固定链仍约慢 4.1%，这一不利观测保留。

### 正确性与结论

- 共 768 次语料查询，修复前后支持状态、可行性、预算截止、库存、缺料及配方执行次数一致；496 个可行计划通过库存、物料平衡与执行回放。缺料和预算受限结果不作为数学不可行证明。
- 共 960 次补料循环调用（含预热），返回缺料加入原库存后，以最小递归搜索重新规划，并全部通过独立原始配方逐次执行回放。正式采样中，每端点共享循环 160 次均为 `{M2=2}`，批量循环 160 次均为 `{M3=1}`。
- 预热阶段两端点的补料向量也一致。
- 补料循环中位耗时下降约 4.1%–10.5%，共享循环分配减少 5.9%，批量循环分配增加 5.0%。ATM、NAST 固定请求的耗时变化均在 ±1% 内。

结果支持保留修复的正确性和补料热点局部收益，没有普遍提速或服务器 TPS 改善证据。有限墙钟预算仍可能返回较保守补料，不保证任意负载下全局最优。

这里使用既有 2026-10-04 清单及 TBR1 单产物物料投影，不等同于更早历史查询，也不代替 AWR v3 加权多产物适配器、真实机器执行或游戏实测。本轮没有新增客户端或服务器运行验证。线程 CPU 原始计数另存，短调用受计时粒度影响，不用其百分比推断收益。

原始样本（含预热、慢样本和不利结果）、脚本及输入/类哈希只保存在本地忽略目录 `build/pr9-benchmark/`。本平台数据位于 `forge/`，长预热复核位于 `longwarm/forge/`；仓库仅记录本节摘要，不上传开发过程或原始日志。
