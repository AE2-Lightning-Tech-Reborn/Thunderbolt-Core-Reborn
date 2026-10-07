# GTL 共享种子 CP-SAT 调度优化（2026-10-08）

本次优化针对 `1.20.1_GTL` 中多个独立循环共享同一种启动材料时的 CP-SAT 有界求解漏解。原有求解器能够找到物料平衡的 firing vector，却可能在共享实际库存的执行顺序上耗尽默认预算并返回未完成结果。优化保留原有产品预算和取消语义，在现有 optional refinement allowance 内增加一个正向可执行见证路径。

## 实现

- `CpSatRankedFlowSolver` 在 small-seed witness 和 native block refinement 之前尝试 compressed seed witness。它只针对至少两个循环、最多 64 种物料和 48 个配方、普通 consumed 输入的图；每个未使用的外部种子生产者最多补一次执行。
- `PetriBlockScheduler` 对固定 firing vector 做有界的 greedy forward scheduling。普通 recipe 和 compact catalog block 都可作为步骤，步骤按可执行重复数与本步骤执行量选取，库存和净变化使用 `BigInteger` 计算，因此支持万亿级重复次数。
- 调度结束后重新生成 `PetriExecutionTrace` certificate，并由 `certifiedPlan` 从 recipe arcs 独立核验物料、主产物和合法补料边界。调度停滞或预算不足只返回 `null`，不生成不可行证明；调用者取消继续向上传播。
- 新路径复用共享 verifier node/work budget，并设置最多 256 个调度阶段。成功结果带有现有 `budgetExhausted`/partial 语义：它证明计划可执行，不证明全局目标已经最优。

这样可避免为每条循环复制实际种子。缺少一单位共享种子时，见证仍报告这一单位缺料；零 node/time/call 预算也不能绕过预算检查。

## 压测结果

压测在 Java 17、Minecraft 1.20.1、Forge 47.1.3、AE2 15.4.10 下执行。before 使用基线提交 `04badd367e6bbe4f375cf19e826b6f961c347f27` 的求解器类，after 使用本次工作树；两者使用相同 classpath、预算和独立 BigInteger recipe replay。每个 cycle 场景运行 3 个 JVM，每个 JVM 预热 3 次并测量 12 次；并发场景运行 3 个 JVM，串行交替 before/after。

下表的 p50 是三个 JVM 各自 p50 的中位数，分配量是各 JVM 单次 allocation 中位数再取中位数。下文并发分项的 p95 也取各 JVM p95 的中位数。缺 seed 场景仍进入后续有界细化，本次快速见证对其耗时没有明显改善。

| 场景 | before p50 | after p50 | 加速 | after 分配量 |
| --- | ---: | ---: | ---: | ---: |
| 2 cycles，数量 10¹² | 22.9583 ms | 4.4839 ms | 5.12× | 0.5375 MiB |
| 4 cycles，数量 3 | 44.9727 ms | 6.6027 ms | 6.81× | 1.0897 MiB |
| 4 cycles，数量 10¹² | 38.7321 ms | 7.1517 ms | 5.42× | 1.0968 MiB |
| 8 cycles，数量 3 | 66.3938 ms | 11.0790 ms | 5.99× | 2.5854 MiB |
| 8 cycles，数量 10¹² | 69.6243 ms | 11.1857 ms | 6.22× | 2.6308 MiB |

库存充足的 216 个 measured cycle 样本全部完成，其中 8-cycle 场景为 72/72。4,800 个并发 measured 查询和 960 个 warmup 查询全部可行。并发 CP-SAT 分项 p50 为 25.7622 → 4.8927 ms，p95 为 29.2853 → 6.2728 ms；混合 V2/CP-SAT 请求的整体 p50 为 4.794 → 4.815 ms，因此不能把混合请求宣称为整体五倍加速。8-cycle、10¹² 数量的单次 allocation 从 4.7291 MiB 降至 2.6308 MiB（约 44%）。

先前基线压测在库存充足的 72 个八循环测量样本中出现 6 次未完成；首次失败为数量 3、测量迭代 8、328.1827 ms，返回 `feasible=false、budgetExhausted=true`。原始记录仍保留在本地 `build/gtl-pressure-20261007`，六次失败诊断也收录于本报告的验证 JSON。本轮 before/after 配对没有再次复现这些失败，不能据此声称故障率已经归零。优化后 432 个 cycle 测量样本都带 budget flag；该 flag 表示未证明最优，不等于未完成。缺 seed 场景耗时约 180–260 ms，且 scheduler 尝试会增加少量分配。

进程 peak working set 的 before/after 最大值分别为 508.660/617.406 MiB，因此单次 allocation 的下降不能解释为进程总内存下降。并发 JVM 的 GC 后堆增长中位数分别约 0.108/0.103 MiB；短时压测不能证明长时间服务器没有泄漏。

## 验证

- Gradle 全量 build：1366 项，1365 通过、0 失败、1 项可选测试跳过。跳过的是 EAEP 集成测试 `ExtendedAePlusSuperMatrixBatchBridgeTest.realScaledPatternPreservesNestedCopiesAndPropagatesSubmissionFailure`。
- 首次定向集合 33/33 通过；随后补加的零 call/time/node 预算及反向 seed 断言在最终全量中通过。共享种子回归和压缩调度两个测试类最终共 13 项通过，其中 300 张随机小图中成功构造的 witness 与独立逐次执行 oracle 一致。
- 独立 `cpSatColdRefinementTest`：1/1 通过。
- `verifyReleaseJar`：通过 refmap、Manifest、Mixin 配置和 JarJar 元数据检查。
- Forge GameTest：第一次启动因 Minecraft 客户端资源索引 JSON EOF 在 `downloadAssets` 阶段失败，游戏测试尚未启动；保留该日志后使用相同依赖加 `-x downloadAssets` 离线重跑，真实 Forge 服务器 10/10 required GameTest 通过。该 GameTest 选择 `thunderbolt:v2`，覆盖 mod、CPU、requester 和发布环境回归，不是新增 CP-SAT scheduler 的专项证明。

发行包为 `build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，SHA-256 为 `d17a956cf71fe4fa018ef2ede69ede8ffed764bab95abd3d5106c31ac0038e25`。完整测试逐项结果、12 个比较 JVM 的原始采样和六次原始失败诊断见 [验证 JSON](validation/gtl-shared-seed-optimization-20261008.json)。更早的共享种子补料问题及修复背景见 [2026-10-07 缺陷审计](gtl-bug-audit-20261007.zh-CN.md)；该历史审计与本次优化前的 37-JVM 压测为不同运行。
