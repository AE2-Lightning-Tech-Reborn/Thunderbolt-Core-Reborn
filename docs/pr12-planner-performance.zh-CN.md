# PR #12：仅保留 CP-SAT 优化

## 最终范围

PR #12 完整合入后，进一步收窄为 CP-SAT 专属优化。V2 没有稳定的整体耗时收益，原整包方案中的 V2 与共用热路径改动已撤回；保留合并前已有的修复与优化。

最终相对 Forge `65eb47a` / alpha `ac04c10`，生产代码仅有以下五个文件不同：

- `CpSatRankedFlowSolver`：优先尝试小型种子见证与紧凑执行块；可选扩展保留原共享预算。
- `PetriBlockCatalog`：复用执行块前缀摘要；该目录的生产调用仅来自 CP-SAT 路径。
- `PetriExecutionTrace`：仅将 `compose` 从 private 改为包内可见，供 CP-SAT 前缀缓存调用；方法体未变。
- `cpsatbridge/CpSatBridge`：向执行块提示传递库存。
- `cpsatbridge/CpSatExecutionBlocks`：生成 seed-once / repeat-many 调度提示；提示不增加约束，最终计划仍由独立证书验证。

`CraftPlannerV2`、`CraftGraph`、`MaterialDagOrders`、`MaterialDagReplay`、`PlannerTopology`、`BoundedIntegerLinearSolver`、`SparseIntegerBounds`、`IntegerResourceCuts`、`UnorderedByproductSafety` 均恢复到各自 PR 前基线。alpha 恢复原内嵌拓扑实现，撤回本轮新加的 `PlannerTopology` 文件。相关 V2 新测试随对应改动撤回，CP-SAT 测试保留并集中到 `CpSatSeedPortfolioRegressionTest`。

## 2026-10-07 最终版本验证

| 环境 | 常规测试 | 冷启动及构建 |
| --- | --- | --- |
| Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10 / Java 17.0.19 | 1290 项：1289 通过，1 项可选集成跳过 | CP-SAT 冷启动细化、独立共享种子测试、test、build、重混淆及 verifyReleaseJar 通过 |
| Minecraft 1.21.1 / NeoForge 21.1.219 / Java 21 | 1276 项：1275 通过，1 项可选集成跳过 | 独立 CP-SAT 共享种子测试及 test、build 通过 |

两端均零失败、零错误。对上述 V2/共用类及其嵌套类逐个比较编译产物 SHA-256，Forge 共 65 个、alpha 共 64 个 class 文件与各自 PR 前基线完全一致；alpha 新增拓扑类也已从编译输出移除。

沿用[既有 ATM/NAST 清单](benchmarks/recipe-corpus-forge-1201-20261004/README.md)，每个平台另外启动独立 JVM，对两场景分别预热 8 次、测量 8 次；每平台全部 32 个计划均可行并通过库存、物料平衡及原配方回放。此次用于验证恢复后的行为，V2 未修改的依据是源码与编译产物一致，不将计时噪声解释为新增性能变化。

## 保留部分的 CP-SAT 性能

针对缩小后的最终版本重新运行对照，基线仍为对应 PR 前版本。使用 PR 的双分支共享种子图：每条分支用一个 S 生成 A，再运行消耗 R 并返回 A 的循环，最终汇合为 T。S 库存为 2，R 为请求量的两倍；请求量分别为 3 和一万亿。

按 before / after / after / before / after / before / before / after 串行启动 8 个独立 JVM，每端点 4 个，参数为 `-Xmx3g -Xss16m`。每 JVM 每场景预热 2 次、采样 6 次；原生库初始化、构图和验证在计时外，每次使用新规划会话。先取各 JVM 中位数，再取 JVM 间中位数。

| CP-SAT 场景 | Forge 前 → 后 ms | 变化 | alpha 前 → 后 ms | 变化 |
| --- | ---: | ---: | ---: | ---: |
| shared-seed-3 | 37.5793 → 7.6158 | -79.73% | 35.9614 → 6.9676 | -80.62% |
| shared-seed-1000000000000 | 47.6937 → 19.8299 | -58.42% | 47.0913 → 19.0041 | -59.64% |

每平台、每端点、每场景的 24 个正式样本与 8 个预热样本全部可行且无缺料。小量独立执行回放；万亿量按原配方验证 seed-once / repeat-many 的符号执行顺序与精确次数。这些是指定 CP-SAT 结构的局部收益，不代表所有请求，也不代表 V2 提速。

## 原整包方案的取舍

原整包版本的 ATM/NAST 中位耗时变化约在 ±2% 内。alpha 批量循环初测慢 19.24%，长预热复核后缩小到 +2.55%（约 0.39 ms）；共享循环长预热快 7.44%。结果有升有降，不能据此认定 V2 整体回退，也没有稳定的整体提速证据。因此撤回其 V2 改动，仅保留有明确耗时收益的 CP-SAT 部分。

这是离线规划测试，未测游戏 GC 停顿、服务器 TPS 或完整整合包兼容性。原整包测量、最终范围对照、源码/类/输入哈希、全部预热与正式样本及复现脚本保留在本地忽略目录 `build/pr12-review/`。开发日志不纳入提交。
