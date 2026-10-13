# Forge 1.20.1 全局压测 - 2026-10-13

## 结论

本轮当前工作树重新编译后的完整 Gradle 门禁、随机压力、重复领域套件、原失败回归、并发 oracle、频道大图和 Forge GameTest 均通过。没有发现新的生产正确性失败；不能据此声称没有 bug，或替代客户端、完整整合包和其他平台验证。

基础 `test` 实际执行 1408 项，1407 通过、1 跳过、0 失败。跳过的 EAEP 真实协议用例随后在带对应 JAR 的领域套件及独立协议任务中通过。Python AWR 测试另有一个真实 reader 用例跳过，未覆盖真实 `awrlib`。

本轮没有修改生产源码、测试断言、规划器预算或原测试时限，没有提交、推送或改变仓库可见性。忽略 GTL 和 Useless 集成，其他工作区的测试结果未计入本报告。

## 环境与方法

- 工作树分支 `1.20`，HEAD `2c89ae6`，包含既有暂存和未暂存改动；不是该提交的干净检出。
- Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10 / OR-Tools 9.15.6755。
- Windows amd64，Temurin Java 17.0.20，项目 Gradle wrapper 8.14。
- 复用已有完整 Gradle 缓存，所有阶段离线执行；`--no-daemon --no-parallel --max-workers=1 --no-build-cache --continue`。
- 首阶段强制 JavaCompile 重新执行；所有 JUnit Test 强制执行且只有一个 fork 同时运行。压力夹具从 Java 源重编译，不借用旧压力测试 class。
- 保持项目 `build/` 路径，避免破坏 `PortMetadataContractTest` 对生成 refmap 路径的约定。大型夹具、报告和 GameTest 新世界放入独立临时目录，未使用用户存档。
- 各压力阶段串行；机器仍可能有其他工作区任务，耗时仅为本机观测，不作 A/B 性能归因。

## 执行结果

| 项目 | 本轮结果 | 说明 |
|---|---:|---|
| `check assemble verifyReleaseJar` | 通过 | 3 分 17 秒；包括 `reobfJarJar` 与 refmap/manifest/JarJar 检查 |
| `test` | 1407 通过 / 1 跳过 | 1408 项，0 失败、0 error；EAEP 真实协议跳过后补跑 |
| `cpSatColdRefinementTest` | 1/1 | 新 JVM，原预算与断言 |
| `materialDagColdReplayTest` | 20/20 | 万亿数量级，fresh recipe identities |
| `materialDagColdReplenishmentTest` | 2/2 | 每类单独 fork，补料证书与 `recoversMaterialDag8089()` |
| `pressureBroad` | 41/41 | 10 个既有随机压力类，1 分 6 秒 |
| `pressureDomainRepeat` | 1277/1277 | 规划器、批处理、大数、存储、频道、NBT、EAEP 等；带 EAEP 1.6.3 JAR |
| `pressureEaepProtocol` | 3/3 | `ExtendedAePlusSuperMatrixBatchBridgeTest`，0 跳过；协议级而非完整机器集成 |
| `pressureOriginalGateRepeat` | 16/16 | 两个历史失败方法各重复 8 次，未更改默认时限或断言 |
| `pressureColdSparse1/2/3` | 各 1/1 | `CpSatSparseRankedTest`，三个独立 JVM，原 3 秒断言 |
| `pressureColdObservation` | 1/1 | 8 次 fresh session 全部 SOLVED；首轮约 148.832ms，无预算耗尽 |
| `pressureSeedObservation` | 1/1 | 32 次 fresh seed 观察全部可行、无缺料、`budget=false` |
| `pressureConcurrent` | 4224/4224 | 4 worker、4096 随机 oracle + 128 共享图万亿 session；约 542.423ms，原总截止 120 秒 |
| `pressureAwr` | 通过 | legacy、加权多输出锚点、物理执行计数、tag、replay、非法输入夹具 |
| `optimizationBenchmark` | 7 个场景完成 | 独立 JVM；无同轮基线，不声称性能提升 |
| `pressureChannels` | 通过 | 5000 oracle + 特殊路径检查 + 9 个规模场景，逐条检查 PASS 而非仅看退出码 |
| `runGameTestServer` | 5/5 | 实际日志为 5 个必需 NBT 用例；不是历史报告中的 14 项 |
| 发布流程 Python 契约 | 14/14 | 无上传、无平台 token |
| AWR Python 适配器 | 3 通过 / 1 跳过 | 未提供 `--awrlib`，实际 v1-v3 reader 未验证 |
| 源码/HEAD/index 前后审计 | 无变化 | 源码、配置和脚本 SHA-256，HEAD 与暂存 diff 均一致 |

上表是多次运行记录，不可相加当作互不重复的测试数量。

## 正确性压力证据

- `PlannerPressureAuditTest`：30000 DAG oracle，`invalid=0`、`executionSuboptimal=0`；16 worker、16000 次并发规划无错误；深度 500/1000/2000/4000，目标量至 10^12。
- `ExpandedMixedPressureTest`：6000 独立 bounded oracle，5095 完整、905 正常预算耗尽，`invalid=0`、`falseComplete=0`；16 worker、16000 session、48000 数量探针，`stockMutation=0`；200 个病理预算场景取消检查通过。
- `StockAwarePressureTest`：6000 库存路线，`invalid=0`、`policyViolations=0`、`executionSuboptimal=0`。
- `IssueStockStressTest`：20000 随机开关场景、60000 次规划，`mismatches=0`，覆盖预留库存、零库存和万亿库存优先使用。
- `Beta1PressureTest`：1200 启动证书（320 补料场景）、72 次反馈回归；24 轮可选阶段过期，共 144 检查，无错误或作用域泄漏。
- `StoragePressureAuditTest`：250000 次 BigInteger oracle 操作、最大请求 4096 bit、250 次持久化往返，`invalid=0`。
- `StorageChurnPressureTest`：78000 次增量操作、900 次往返；16384 bit 上限 1000 轮，未接受未存入数量，非法值未污染状态。
- `SparseRankedPressureTest`：千配方的 side/container/finite/catalyst/private/cycle/feedback 七类全部 SOLVED；cycle 约 54.397ms，feedback 约 596.874ms，均在原 3 秒断言内。
- 历史 `FeasiblePolicyAlternativesTest.joiningTwoBranchesCanAvoidAWholeIntermediateBatch()` 与 `CpSatSparseRankedTest.oneSpecialRecipeDoesNotRequireADenseThousandRecipeGraph()` 本轮全量、领域及重复任务均通过。有限重复不能证明间歇性问题永久消失。

## 频道与游戏内验证

频道使用真实 AE2 `GridNode`/`GridConnection` 与生产 `BorrowedCapacityCalculator.assignChannels()`，仍是合成图，不是完整游戏世界。每个规模场景独立 Java 17 JVM，`-Xss1m -Xmx2g`，3 次预热、7 次测量。

| 场景 | 节点数 | 实际流量 | 冷态 ms | p50 ms | 最大 ms |
|---|---:|---:|---:|---:|---:|
| 深链 100000 | 100267 | 1 | 367.999 | 93.375 | 104.629 |
| tree 1000 | 1266 | 1000 | 51.049 | 3.175 | 4.240 |
| comb 1000 | 2266 | 1000 | 64.692 | 3.105 | 5.286 |
| tree 136192 | 226898 | 136192 | 678.393 | 361.594 | 381.862 |
| tree 200000 | 290706 | 136192 | 766.088 | 451.902 | 518.530 |
| mesh 136192 | 272650 | 136192 | 800.055 | 480.379 | 509.763 |
| dense 136192 | 226898 | 136192 | 677.241 | 330.884 | 361.443 |
| vanilla 136192 | 226898 | 136192 | 636.525 | 320.638 | 352.216 |
| weighted 512 | 532 | 136192 | 27.455 | 1.971 | 2.309 |

全部 PASS，未出现 `STACK_OVERFLOW`。tree 200000 达到供给上限 136192 是预期值。mesh 测得 heap pool peak 合计约 651.833MiB；这不是全进程 RSS。大网络单次重算仍可能造成主线程卡顿，正确性通过不等于满足 50ms tick 延迟。

Forge GameTest 在新临时世界运行；`thunderbolt.mixinAudit=true` 审计已选择的 Mixin。`NbtCopyGameTests` 的启停路径、嵌套可变后代隔离、自定义容器、复制异常状态清理及随机嵌套图全部通过。未安装的可选模组、客户端 Mixin 和 GUI 不在该证明范围；CP-SAT 游戏配置仍为默认禁用，独立原生测试不能替代 Forge 内启用后的验收。

## 首次失败与生成物修正

首次频道 JavaExec 直接调用 `ChannelStress.main()`，在创建节点时出现 `ExceptionInInitializerError`，根异常为 `Not bootstrapped`，未执行到频道算法。对照既有 `AlgorithmComparison.main()`，在忽略目录内新增启动器调用 `SharedConstants.tryDetectVersion()`、`Bootstrap.bootStrap()` 后再调用原夹具。重跑全部场景通过；未修改原夹具、算法或断言。首次失败日志保留。

新 JAR 与 10 月 10 日遗留 `.sha256` 不一致。仅更新忽略构建目录中的校验和；独立发布包审计验证 515 个 class 全部 major 61、许可证保留、Mixin 类齐全、refmap 有 mappings、JarJar 仅嵌入 MixinExtras、CP-SAT Java/protobuf 资源存在，未打入 Useless 或测试夹具。

发布包：`build/libs/thunderbolt-forge-1.20.1-2.0.1-beta.1.jar`，5382754 bytes。

SHA-256：`14d7ed7a6686626e45411ecdace9ab18341a2af87f5417cd05b6619a113b8383`。

## 证据与剩余边界

运行脚本与启动器位于 Git 忽略的 `build/global-pressure-20261013/`。`output-path.txt` 指向本轮独立临时输出目录；其中保存各阶段 stdout/stderr、实际退出码 JSON、JUnit XML/HTML、并发结果、`summary.json` 与 `release-jar.json`。报告未包含本机开发日志或凭据，不将这些运行产物加入提交。

本轮消除了上一轮完整 Gradle 配置受阻的验证缺口；不是干净 CI 检出的复现证明。仍建议 CI 在独立 Java 17/Linux 环境复验，并补充真实 EAEP 机器、CP-SAT Forge 启用、客户端 GUI、完整整合包、真实 AWR 数据以及长时世界/重载/GC 测试。可以进入提交前审查与 CI 复验，但仅此不能宣布 beta 发布验收完成。
