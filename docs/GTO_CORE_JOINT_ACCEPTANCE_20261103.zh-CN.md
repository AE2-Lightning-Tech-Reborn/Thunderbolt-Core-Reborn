# Core 2.0.4-gto.5：跨项目验收与直接联测汇总

本次补齐外部退款 UNKNOWN 契约及第⑧/⑨步记录。Core 生产代码、公开 ABI、版本和
固定发行包沿用已验收的 `.5`；新增交付为契约和验收文档。Core 本轮全量与双版本
实机复测均通过；LT 报告的独立定向 Core 测试曾有一次取消监视超时，原预算复跑
通过但根因未明，作为独立记录保留。机器摘要见[基线](baselines/core-joint-test-20261103.json)。

| 固定输入 | 身份 |
| --- | --- |
| Minecraft / Forge / Java | 1.20.1 / 47.4.20 / 21.0.10 |
| Core 坐标 | `com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1:2.0.4-gto.5` |
| 正式 Core 文件 | `thunderbolt-forge-1.20.1-2.0.4-gto.5.jar`，5,472,513 字节 |
| Core SHA-256 | `902c439f64c13ca776fb28c4c9c29aca70fe3083b0b65b5e744cad399f1bb2a1` |
| 原始发行源码 | `19e1e6fa3f9b93330f038b2d4d76f1d4fc3f9afb` |
| Core 本轮受测源码 | `1d6abc80c2f39a7c77c0d875a8e1489ce3c2d76e`；与原始发行源码仅差三份契约文档 |
| LT 报告的受测源码 | `1d1cf9d8ef8e87bf5b325de8ccdc5c548f325fac` |
| LT 最终 R6 正式包 SHA-256 | `4d3cec2c987f8511fb427b495f92bb65319ebad225f044502f621660b714e8ae` |
| AE2-GTO | Dev23：`15.2610.31`；Dev25：`15.2610.40` |

固定发行目录、项目 Maven 仓库与 Maven Local 的 Core 三份副本在本轮测试前后哈希
一致。本次文档整合不重建该包；CI 重新构建的产物按自身哈希区分。

## 外部退款 UNKNOWN 契约

[契约补充](GTO_CORE_REFUND_UNKNOWN_CONTRACT_20261102.zh-CN.md)明确现有 Core 接口足以
让 LT 持久化隔离不确定退款。外部先接收 2、再抛出无回执异常时，不能将原 offer 10
当成可再次退款库存。调用前隔离实际 offer；合法回执只结算一次；无/非法回执、
异常或恢复到未完成调用时保持 UNKNOWN，等待权威对账。

隔离敞口不是额外库存。恢复、取消、完成清理及拆除均须保留唯一对账责任，不能
重放或物化隔离量。LT 后续修复由 LT 提供实际验收证据，Core 未新增共享接口。
正常保存退出与恢复通过不构成任意断电下的跨模组原子性或自动 exactly-once 保证。

## Core 本侧新执行结果

批次 `direct-joint-20261103-core` 使用独立构建目录、XML 和新世界，完成于
`2026-11-03T04:46:17.468064+00:00`。以下各任务覆盖有重叠，按任务报告，不加总为新增用例。

| 检查 | 结果 |
| --- | --- |
| 全量 `test` | 1695 项：1693 通过、2 项缺可选依赖跳过、0 失败/错误 |
| `referenceCapabilityTest` | 82/82 |
| `cpSatColdRefinementTest` | 1/1 |
| `materialDagColdReplayTest` | 20/20 |
| `materialDagColdReplenishmentTest` | 2/2 |
| 实际 AE2 `.31` 官方开发映射 ABI | 14/14 |
| 实际 AE2 `.40` 官方开发映射 ABI | 14/14 |
| 固定原始 `.5` 包：Dev23 Forge EJECT/ElementalCraft | 8/8 |
| 固定原始 `.5` 包：Dev25 Forge EJECT/ElementalCraft | 8/8 |

全量中的审查回归 `SmallRecoveryBudgetStatusTest` 13 项及
`FastPlannerUnknownOutcomeTest` 4 项均通过，包括恢复/共享工作额度耗尽及 A5/B14
请求 T23 的可执行 T26 对照。`CpSatBudgetBoundaryTest` 6 项、
`BatchAccountingOverloadsTest` 2 项、`BatchCpuAccountingTest` 4 项也均通过。
两项跳过分别因 JUnit 运行时未提供可选 ExtendedAE Plus 与 Useless 公共 API 制品。

ABI 任务使用实际 AE2 JAR，仅映射 Minecraft 名称；Forge 专服使用原始发行 Core
和原始 AE2。EJECT/ElementalCraft 测试涵盖能力归属、离线拒绝、投射递归/异常 guard、
物品/流体派发与注册生命周期，两个服务端均正常退出且全部模组输入哈希保持一致。
Core 独立实机环境按本项目约定排除 `neoecoae`，没有新增其集成或夹具。

## LT 侧报告与独立超时记录

以下来自 LT 的 `joint1103r1` 直接联测和 [PR #54](https://github.com/AE2-Lightning-Tech-Reborn/AE2-Lightning-Tech-Reborn/pull/54)
说明，保留 LT 报告身份，不冒充 Core 本侧执行结果：

- LT GameTest 69/69：Dev23 25 项，Dev25 44 项，含 exact/BigInteger/混合派发。
- 两版真实 FX 及每版三个独立 prepare/resume/verify JVM 通过。
- UNKNOWN 经真实世界 SavedData 恢复保持 physical=2、spendable=0、unresolved=10，
  owner/job UUID 保持，恢复、取消和拆除不重放。
- 最终 R6 图形/TCP 多人 6/6，28 个截图哈希、12 个已目视核对面板、16 个真实 GUI ACK、
  32 次实际重连登录；直接覆盖最终 R6 生产字节。
- 同一原始三秒绝对期限、排队取消及正常成功三个预览控制通过。

LT 的独立 Core 定向源码测试首轮为 93 项：**92 通过、1 超时、0 跳过**。
失败项 `PlanningAttemptMonitorTest.unmanagedInterruptStillReportsNativeCancellation()`
在第 43 行 `outcome.get(2, SECONDS)` 超时，报告测试耗时 2.105 秒。原两秒限制及
全部断言不变的新 JVM 复跑 1/1 通过，报告测试耗时 1.292 秒。首次失败仍保留；
只读分析没有失败 worker 的边界栈，**根因未确定，不能把该运行写成全部通过**。
同轮 P1 两类 17/17、实际 AE2 官方映射 ABI 两版各 14/14；这些不是首轮超时被修复的证据。

Core 本侧全量通过、LT 独立首轮超时及其诊断复跑属于不同执行身份；本次汇总不互相
覆盖结论。LT 联测机器清单 SHA-256 为
`6746d10be12633b625d9fed9c1020dead2d032d5b64d8415f23067ed2b520227`，
其 Core 原始 XML/日志索引 SHA-256 为
`0b02a8651138e9c1ebeaa03a6ffe85a375b81c53f9c69f37f421f1f00d7c6ef1`。

## 验证边界与交付

本次未增加性能测量。既有[发行验收](GTO_CORE_ALGORITHM_RELEASE_20261102.zh-CN.md)的
默认 `legacy-nine-distinct +51.6%`、独立同步组同例 `+4.8%` 与共享种子 `+1.3%`
回退信号仍保留，不宣称稳定提速或无回退。

原始机器日志、XML、截图与失败回执保留在各自本地批次；仓库收录脱敏摘要、输入
哈希和证据摘要哈希。Core 生产代码与测试等同固定 `.5` 的受测源码，开发过程提交
继续保留本地；PR #17 仅上传整理后的汇总提交，目标分支为 `gto`。PR 更新不等于
合并或公开发行，跨项目发布及该一次超时的根因仍需分别跟踪。
