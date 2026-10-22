Forge 1.20.1 GTL 上游移植与验证（2026-10-22）

对照[普通上游 1.20.1](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/tree/cea4cc29b97e308987ad4a06a2c61f7fb00f0527)，上游提交为 `cea4cc29b97e308987ad4a06a2c61f7fb00f0527`，本地移植基线为 `fbde81452833fc0fdcc7e0983aa9b9df2ead226e`。本轮补齐此前差异中的小规模 DAG 批次优化、冷启动补货回归、正式 NBT GameTest 和对应 CI。验证环境为 Minecraft 1.20.1、Forge 47.1.3、AE2 15.4.10、Java 17；NeoECO 按要求持续排除。

- 接入上游 `SmallDagBatchOptimizer`：最多 12 个配方、24 次执行、4096 个搜索节点，共享工作预算与取消检查，替换方案须通过库存回放。新增 4 项行为测试，覆盖共享中间批次从 5 次降到 4 次、拒绝缺库存方案、预算耗尽后重试和外部取消。
- 移植新配方身份的补货承诺回归，新增 `materialDagColdReplenishmentTest`，每个测试类独立 JVM，接入 `check`。
- 原样移植上游 5 项 NBT GameTest 和结构资源，默认 `runGameTestServer` 使用 `thunderbolt_nbt` 命名空间。覆盖实际 Mixin 调用路径、可变子节点隔离、自定义容器、异常后重试及 128 个固定种子嵌套图。
- 修复开发运行的源集顺序：Forge 从首个源集发现模组清单，`main` 必须先于 `gameTest`。修复前曾发生菜单注册失败而 Gradle 仍成功；新增 CI 同时检查服务器日志中的 5 项必需测试通过标记。
- 将 5 项毫秒级线程时序和参考期限检查放入 `planningLifecycleTimingTest`，独立于主套件的 Minecraft/原生初始化、GC 和 JIT 历史；保留原断言与限时，普通 `test` 和 `check` 均会执行。

本地 GTL CPU 让位、长数量与精确字节成本、取消/预算约束、126 位单键存储和 Forge 分离快照等修复继续保留。整数解复用继续由 `FeasibleWitness` 逐模型复核。

最终完整 `check` 的 7 个 Test 任务全部实际执行，未复用测试结果：1563 次通过、1 次跳过、0 次失败或错误。其中 23 次为独立 JVM 重复检查；去重后为 1540 项通过、1 项跳过。

| 任务 | 通过 | 跳过 | 失败/错误 |
| --- | ---: | ---: | ---: |
| `test` | 1496 | 1 | 0 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 |
| `cpSatColdRefinementTest` | 1 | 0 | 0 |
| `materialDagColdReplayTest` | 20 | 0 | 0 |
| `materialDagColdReplenishmentTest` | 2 | 0 | 0 |
| `planningLifecycleTimingTest` | 5 | 0 | 0 |

跳过项为 `ExtendedAePlusSuperMatrixBatchBridgeTest.realScaledPatternPreservesNestedCopiesAndPropagatesSubmissionFailure`：当前 JUnit 运行类路径没有提供该真实 EAEP 可选实现，原测试按条件跳过。

| 其他验证 | 结果 |
| --- | --- |
| 上游 NBT GameTest，默认兼容模式，Mixin 审计开启 | 5/5；18 个审计目标 |
| 本地兼容、存储、端点生命周期、NBT GameTest，GTL `always` 模式 | 13/13 |
| 实际变换字节码中的 NBT 复制钩子 | CompoundTag/ListTag 各 2 个钩子，均存在 |
| 批量库存独立随机 oracle，种子 20261022、20261023、20261024 | 60000 个输入案例 + 6000 个多提供者案例，全部通过 |
| 发布脚本契约 | 12/12 |
| GitHub Actions 工作流语法 | actionlint 通过 |
| `assemble`、`verifyReleaseJar` | 通过 |

前序全量尝试出现过 1 秒回放用例超时、CP-SAT 冷启动 `UNKNOWN` 和线程时序断言失败；参考期限测试还曾在 250 毫秒窗口内触发超时。相关生产实现与移植基线一致。采用现有边界测试的独立 JVM 方式隔离 5 项检查后，原断言、预算和限时均保持不变，定向复验及最终完整复验通过。配套 JSON 保留前序失败明细；冷启动及线程调度的时序稳定性仍属于风险观察项。

两组 Forge 服务器均实际完成全部必需测试，并正常保存世界退出。GTL 场景使用现有注入夹具与 `always` 模式；整包长期 TPS 和图形客户端人工交互需要独立验收。

发行包为 `build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，大小 5405705 字节，SHA-256：`c76ce326b755a84d57a9295ff0eb64317645712063a3dd7f606aabbb7441ec3a`。

发行包审计确认主类为 Java 17 字节码，嵌套依赖兼容 Java 17，生产 Mixin 配置与 refmap 一致，NBT shadow 已重混淆，旧 Result 构造器和精确字节成本 ABI 保留，新增优化器已打包且生产调用存在；包内无测试夹具、NeoECO 条目或引用。

复现入口为 `./gradlew check assemble verifyReleaseJar --no-parallel --max-workers=1`。上游 NBT 运行验证为 `./gradlew runGameTestServer`；本地兼容验证为 `./gradlew -I tools/gtl-compatibility-game-tests.gradle runGameTestServer`。本次临时世界和原始环境日志保留在提交范围之外。

[结构化验证结果](benchmarks/gtl-1201-upstream-port-20261022.json)
