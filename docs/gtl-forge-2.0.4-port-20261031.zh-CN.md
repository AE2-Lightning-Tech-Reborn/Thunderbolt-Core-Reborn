# Forge 1.20.1 → GTL 2.0.4 移植与验证记录

本轮将普通 Forge `1.20.1` 的 `cea4cc2..fa6d16e` 增量移植到 GTL `36c44bb`，版本更新为 `2.0.4`。Core 仓库内验证通过；完整 GTL 整合包验收仍须由 ae2lt 执行。开发过程保留在本地，最终整理为一个汇总提交合入 `1.20.1_GTL`，未推送。

- 源提交：`fa6d16e40ffdfc95ea0b8b74f9a35a2d87f1bc1e`；源增量基线：`cea4cc29b97e308987ad4a06a2c61f7fb00f0527`。
- GTL 起点：`36c44bbb3198a974aeec44d214fa3efb9aebd005`；Maven 坐标：`com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1-gtl:2.0.4`。
- 结构化结果和修改路径清单：[验证数据](benchmarks/gtl-forge-2.0.4-port-20261031.json)。本轮共修改或新增 105 个路径。

移植包含运行时批量派发与供应器缓存、身份队列和样板输入缓存；精确执行证书、字节进位及索引存储边界；长数量/精确请求公共 API；Useless BigInteger / Smart Doubling 公开协议的可选反射适配；规划器单位链、稀疏组合、撤销日志及整数证明复用。普通 long 请求保留 AE2 的规划及 CPU 准入路径；新增 exact API 需要外部可用执行后端，Core 本身不提供任意大数执行后端。

保留 GTL 的 CPU/可选供应器让位、通道优化及原有本地规划器修复。NBT 保留 beta4 的 Radium 共存路径，为 Lithium/Canary 增加注入前停用门控。既有 NeoECO 兼容不清理，本轮未导入上游新增 NeoECO 适配、注册语义、依赖或测试夹具。15 个受保护文件（含 AGENTS.md 和 NeoECO 源码/测试）、27 个既有未跟踪文件及 beta4 发行包均核验未改；没有改动其他项目目录。

完整检查 `check assemble verifyReleaseJar generatePomFileForMavenJavaPublication --continue` 通过，JUnit 总计 **1723 项：1722 通过、0 失败、0 错误、1 跳过**，耗时 204.309 秒。跳过项为 `UselessBatchAdapterTest.installedPublicApiMatchesBridgeSignatures`，原因是缺少提供当前协议的真实 Useless JAR。EAEP 1.6.3 的三项协议测试已实际执行通过。

| JUnit 任务 | 总数 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| `cpSatColdRefinementTest` | 1 | 0 | 0 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| `materialDagColdReplayTest` | 20 | 0 | 0 |
| `materialDagColdReplenishmentTest` | 2 | 0 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 |
| `planningLifecycleTimingTest` | 5 | 0 | 0 |
| `test` | 1656 | 0 | 1 |

实机 GameTest 每组使用独立 JVM 和新世界，**65/65 通过**，各组正常保存并退出。GTL 组使用入口夹具验证让位与长数量桥接，不能替代完整 GTL 整合包验收。EAEP 组加载真实 EAEP 1.6.3，覆盖矩阵倍率、拆除重建、满存储恢复和 AE2 CPU 扣料交付。

| 组别 | Forge | 通过 | 正常保存退出 |
| --- | --- | ---: | --- |
| `nbt-baseline` | 47.1.3 | 14/14 | 是 |
| `canary-default` | 47.4.20 | 14/14 | 是 |
| `radium-default` | 47.4.20 | 5/5 | 是 |
| `radium-disabled` | 47.4.20 | 14/14 | 是 |
| `gtl-fixtures` | 47.4.20 | 14/14 | 是 |
| `eaep-machine` | 47.4.20 | 4/4 | 是 |

首轮完整检查曾有四项独立组合规划回归和一项候选保留回归失败，原始证据保留。产品修复将新传播路径接回 GTL 已完成的分量证明缓存，要求完整模型匹配并保留实际库存认证。两项可选搜索夹具增加等价路线，确保新传播能够提前解出简单模型后，夹具仍进入原先要验证的搜索路径；候选保留夹具则因上游稳定排序变化调整路线数量。原时间/工作量预算和结果断言均保留；定向回归及后续完整检查通过。

最终发行包由默认 Forge 47.1.3、Java 17、AE2 15.4.10 环境重新构建，`assemble verifyReleaseJar generatePomFileForMavenJavaPublication optimizationBenchmark` 通过。发行包审计确认 Java 17 字节码、无重复 ZIP 条目、无 GameTest/兼容夹具、Mixin 配置和 refmap 与生成结果一致、许可证和 MixinExtras JarJar 完整。7 个独立场景的基准 smoke 通过可行性/守恒断言；未运行前后配对基线，不据此宣称性能提升。

- JAR：`build/libs/thunderbolt-forge-1.20.1-2.0.4.jar`，5469461 字节。
- SHA256：`b63453d02b6acb41117eb5980b2d2e49e3c67b65189be19e4fcd04e5c6379cc4`。
- 冻结副本：`build/gtl-port-20261031/release/thunderbolt-forge-1.20.1-2.0.4.jar`；同目录提供 `.jar.sha256`。
- 本地原始日志、JUnit XML、脚本、世界和审计结果位于 `build/gtl-port-20261031/`，未加入提交。

交给 ae2lt 后仍须验证完整 GTL 客户端/专服冷启动、原存档独立 JVM 重启、默认 Radium、合成提交/取消材料与能量守恒，以及真实导入导出和原性能预算。可获得提供当前公开协议的 Useless JAR 后，再补真实 API 和机器集成验证。
