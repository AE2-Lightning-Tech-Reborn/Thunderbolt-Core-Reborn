Forge 1.20.1 GTL 大图库存查找修复（2026-10-22）

修复基线为 `1.20.1_GTL` 的 `f20193759b658728fe0143accaf7289b2dc900b4`。本次修复针对全面复测中 4096 分支 DAG 偶发超过原有单次 3 秒限时的问题。验证环境为 Minecraft 1.20.1、Forge 47.1.3、AE2 15.4.10、Java 17.0.20、Windows amd64；NeoECO 持续排除。

已确认的性能问题是 Java 17 的 `Map.copyOf` 对大库存生成 `MapN`，未扩散的哈希与线性探测可能形成长簇，反复放大库存查询成本。对实际大图的 JFR 采样在修复前捕获到库存查询进入 `MapN.probe`，确定性回归用例也独立复现了退化。这确认了一个耗时来源，但不能证明历史超时的全部成因都来自此处。

`CraftGraph` 现在对超过 16 项的普通库存和精确库存使用独立的 `HashMap` 副本及只读包装，小库存继续使用 `Map.copyOf`。初始构建、补充库存、限制库存、扣除库存均采用同一规则；配方投影共享已有只读库存。精确整数、饱和投影、快照隔离及空键拒绝行为保持一致。代价是大库存需要更多映射条目对象。未修改求解预算、既有断言、数据规模或限时。

新增 3 项回归测试覆盖哈希簇、库存投影及隔离、超出 long 范围的精确库存与饱和行为、大小库存的空键拒绝。哈希用例使用 2048 个不同哈希值但落入同一个 `MapN` 初始桶的键，同时查询存在和不存在的键；旧实现发生 12584960 次相等比较并失败，修复后 5 类投影均不超过每类 32768 次比较上限。该断言衡量查询工作量，无墙钟限时。测试也确认原图不受后续 builder 或调整映射修改的影响。

最终代码的完整 `check assemble verifyReleaseJar` 通过，7 个 Test 任务强制重跑，未复用测试结果。合计 1566 次通过、1 项跳过、0 次失败/错误；去重后 1543 项通过、1 项跳过，其余 23 次为重复覆盖。

| 任务 | 通过 | 跳过 | 失败/错误 |
| --- | ---: | ---: | ---: |
| `test` | 1499 | 1 | 0 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 |
| `cpSatColdRefinementTest` | 1 | 0 | 0 |
| `materialDagColdReplayTest` | 20 | 0 | 0 |
| `materialDagColdReplenishmentTest` | 2 | 0 | 0 |
| `planningLifecycleTimingTest` | 5 | 0 | 0 |

跳过项为 `ExtendedAePlusSuperMatrixBatchBridgeTest.realScaledPatternPreservesNestedCopiesAndPropagatesSubmissionFailure`：锁定的 EAEP Forge 包不提供该可选开发版协议，现有旧版形状与降级测试仍执行。

在完整检查之外，10 个独立 JVM 各运行一次原始 4096 分支大图用例，全部通过；每个用例仍覆盖请求量 1 和 1000000000000，保持原有每次规划调用 3 秒限时，以及可行性、单次求解和库存守恒断言。

同一自然大图场景的 JFR 采样中，旧实现 163 个执行样本有 16 个落在库存 `MapN.probe` 路径；修复后 192 个样本中该路径为 0。采样支持大库存查询避开该探测路径的判断；不以少量探索计时推导固定加速比例。

Forge GameTest 默认 NBT 组 5/5、GTL 兼容组 13/13 通过。两组均核对真实服务器日志、世界保存与退出，并检查实际变换字节码：`CompoundTag`、`ListTag` 的 `fuseTransform` 和 `collectCopy` 各出现一次。兼容组使用现有注入夹具与 `always` 模式，结束后生成目录中的 Mixin 配置恢复为生产版本。可选 Fernflower 源码反编译器缺失不影响实际类文件导出与字节码检查。

发行包检查通过：主类均为 Java 17 字节码，嵌套依赖兼容 Java 17，生产配置与 refmap 一致，NBT shadow 已重混淆，旧 Result 构造器和精确字节成本 ABI 保留，小型 DAG 优化器及其生产调用已打包。发布字节码中 8 处库存快照调用、大库存 HashMap、只读包装及空键检查均存在。无游戏测试夹具、NeoECO 条目、引用或可选依赖。

发行包：`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5406039 字节，SHA-256：`1a94a63e6e6bcaf88a94273395de1afa2db1e3fc9e75cf2142254d9fdb7af411`。此前报告中的校验和对应此前产物。

标准完整检查入口为 `./gradlew --no-parallel --max-workers=1 check assemble verifyReleaseJar`；默认 Forge 入口为 `runGameTestServer`，兼容入口为 `./gradlew -I tools/gtl-compatibility-game-tests.gradle runGameTestServer`。本轮强制重跑、独立 JVM、隔离世界配置和原始证据保留在忽略目录 `build/dag-timeout-fix-20261022/`；报告只记录脱敏结果，不提交本机日志。

有限次时序验证不能保证任意系统调度或资源压力下都满足限时。本轮未覆盖图形客户端人工操作、多人联机或完整 GTL 整合包长期 TPS，也未运行远端 CI。修复及报告整理为一个本地汇总提交，不推送。

[结构化结果](benchmarks/gtl-dag-stock-fix-20261022.json)
