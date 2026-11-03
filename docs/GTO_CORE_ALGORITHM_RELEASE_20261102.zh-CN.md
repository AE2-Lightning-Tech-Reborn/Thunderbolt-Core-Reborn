# Core 2.0.4-gto.5：算法续办与本地发行验收（第⑦步）

图导出或搜索截断、恢复额度耗尽时，Core 现在保留 UNKNOWN，不将未完成诊断发布为
确定缺料。CP-SAT 的准备与认证计入继承预算，取消保持原义传播；批量最后一份共享
种子的重复退款不再增加库存。保留普通完整缺料回退、认证可行结果、完整键身份、
双 AE2 ABI 及普通 `1.20.1` 上游 `d67e956` 的 EJECT/ElementalCraft 修复。

## 制品身份与获取

| 项目 | 值 |
| --- | --- |
| Minecraft / Forge / Java | 1.20.1 / 47.4.20 / Microsoft OpenJDK 21.0.10 |
| 新坐标 | `com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1:2.0.4-gto.5` |
| 正式文件 | `thunderbolt-forge-1.20.1-2.0.4-gto.5.jar`，5,472,513 字节 |
| SHA-256 | `902c439f64c13ca776fb28c4c9c29aca70fe3083b0b65b5e744cad399f1bb2a1` |
| 本地发布 | Maven Local 与项目 `repo/` 的新 `.5` 坐标；两个副本逐字节一致 |
| 对照 | 固定 `.4`：`734d9bf152a66dee5bf69397106b6808a64305969459ba60c2e8f7139c08c7cd` |
| 源码交付 | 接续既有 [PR #17](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/pull/17) 的一个新汇总提交；实际源码 SHA 以该 PR 交付记录为准 |

冻结的本地获取位置按内容哈希命名，创建后只核验、不覆盖：

```text
D:/CodexTaskData/thunderbolt-core-gto-algorithm-20261102/release/902c439f64c13ca776fb28c4c9c29aca70fe3083b0b65b5e744cad399f1bb2a1/thunderbolt-forge-1.20.1-2.0.4-gto.5.jar
```

项目 Maven 路径为
`repo/com/moakiee/thunderbolt/thunderbolt-reborn-forge-1.20.1/2.0.4-gto.5/`，
仓库内文件名前缀为 `thunderbolt-reborn-forge-1.20.1`，内容 SHA 与上表一致。
发布前确认新坐标未占用；发布后核对 14 个既有 JAR 哈希未改变。
LT 第⑧步必须使用以上同一完整重混淆包及 SHA；CI 重新构建产物不能自动视为本包。

## 实现、审查及兼容

规划独立复现及审查退回修正见[规划报告](GTO_CORE_PLANNING_AUDIT_20261102.zh-CN.md)，
退款守恒见[批量报告](GTO_CORE_BATCH_AUDIT_20261102.zh-CN.md)。开发过程提交保留在
本地，公开交付把 `.4` 上游移植和本次已验收增量整理为一个新提交，接续既有 PR 头
`2670dc3f9fff738b2416ba5d14fdb2a4575b4d17`，不上传过程分支。

频道审查卡启动失败后，独立只读子代理恢复④/⑥。④首次 REJECT 的同会话恢复额度
缺口已有旧生产红测和修正，复审 PASS；⑥内部退款及初始基准 PASS；最终制品的新
18 JVM 证据亦获补充 PASS。审查范围与限制见[审查记录](GTO_CORE_ALGORITHM_REVIEW_20261102.zh-CN.md)。

82 个冻结共享源文件、10 个保留锚点未变。只读接入 LT 第②步指定的三份资料，
没有修改 LT。最终正式 `.4/.5` 的 110 个消费类型结构保持一致，其中 108 个字节
完全相同；两个发生内部字节变化的类型为 `ParallelBatchCpuHelper` 与 `BulkResult`。
351 条引用/207 个不同成员对应的类型结构、两个反射构造器及七个注入/Shadow 锚点
均保持。没有发现必须新增的 LT 公开接口；静态结构核对不等同于 LT Mixin 实际应用。

保留许可证和署名，NeoECO 固定 `381e3f844beca250b0f824017f0fe585c9364144`
仅作行为参考，没有复制 GPL 实现/夹具或恢复排除的集成、依赖、注册和配方。
Core 独立服务端验收按本项目范围排除 `neoecoae`；外部桥组合由 LT 第⑧步验证。

## 最终包验证

| 检查 | 结果及边界 |
| --- | --- |
| wrapper `check` / `build` / `verifyReleaseJar` | 通过；557 个 Core class 均为 Java 21 字节码，唯一 refmap、Mixin、嵌套依赖、许可证和署名通过，无 QA 混入 |
| 主 JUnit | 1,695 项：**1,693 通过、2 项缺可选依赖跳过**，零失败/错误 |
| `referenceCapabilityTest` | **82/82**；不与主任务重复统计为新增用例 |
| 独立冷启动任务 | CP-SAT 1/1、Material DAG replay 20/20、replenishment 2/2 |
| 原始 AE2 `.31` / `.40` 各经官方开发映射的 ABI 任务 | 各 **14/14**，两份 XML 独立保存；属于开发映射验证 |
| 同一正式 `.5` 包：Dev23 / AE2 `.31` | **8/8** EJECT/ElementalCraft GameTest，GTOCore 26.10.10 |
| 同一正式 `.5` 包：Dev25 / AE2 `.40` | **8/8** EJECT/ElementalCraft GameTest，GTOCore 26.10.18 |

两套服务端均使用 Forge 47.4.20、真实 ElementalCraft 6.0.1 与 dpanvil 5.0.1，
核对 Core 和所有模组输入在运行前后哈希不变。测试覆盖物品/流体派发、离线拒绝、
能力原生归属、嵌套/自身投射、异常后 guard 清理、多主机、关闭及替换注册的生命周期，
并实际调用受 ElementalCraft Mixin 影响的附魔方法。

首轮 `.5` 启动被旧 QA 自身的 `[.3,.5)` 版本范围拒绝，原始失败完整保留；新 QA
副本仅将 `META-INF/mods.toml` 范围锁为 `[2.0.4-gto.5]`，断言、生产包、依赖与预算
不变，两个新独立目录重跑通过。没有将该夹具错误当作 Core 产品失败或通过。

## 最终制品性能原始样本

before 为固定 `.4` 正式包的官方开发映射副本；after 为本次同一 `.5` 正式包的映射
副本，SHA-256 `3386b0f93768609a3aeb2e59a699fe5ea9d0fa9225d43b54c8290cbc3876d88d`。
两侧实际从各自 JAR 加载 helper/planner/executor，逐项校验来源及 class SHA，
没有以当前类目录冒充 before。复用相同测量入口、固定场景、库存、迭代和预热。

默认组共 12 个新独立 JVM：两个入口，每侧各三次。另有 6 个独立 `-Xbatch` 批量
诊断 JVM，与默认组分别报告。相同 Java 21.0.10、G1、Xms512m/Xmx1024m；串行
交错两侧，第二轮反转顺序。每场景保留首次调用、20 次有序测量、分配、GC 和主机
采样。只有每个新 JVM 的首场景属于 JVM 冷启动，后续首次调用均已受先前场景影响。

下表为三个 JVM 各自 p50 的中位数，单位 ns/op；属于描述性统计，不是因果置信区间。

| 组 | 入口 | 场景 | .4 before | .5 after | 变化 |
| --- | --- | --- | ---: | ---: | ---: |
| default | batch | native-one-slot | 991.5 | 955.2 | -3.7% |
| default | batch | native-nine-repeated | 2444.5 | 2224.9 | -9.0% |
| default | batch | native-nine-distinct | 2360.3 | 2339.0 | -0.9% |
| default | batch | native-nine-reserved | 2390.1 | 2346.0 | -1.8% |
| default | batch | native-shared-seed | 2497.9 | 2450.4 | -1.9% |
| default | batch | legacy-nine-repeated | 1586.6 | 1556.1 | -1.9% |
| default | batch | legacy-nine-distinct | 1291.1 | 1957.2 | +51.6% |
| default | optimization | chain-500 | 782690.0 | 730510.0 | -6.7% |
| default | optimization | alternatives-1 | 35568.0 | 24696.0 | -30.6% |
| default | optimization | quantity-session-1 | 178240.0 | 164900.0 | -7.5% |
| default | optimization | alternatives-1000000000000 | 23012.0 | 20790.0 | -9.7% |
| default | optimization | quantity-session-1000000000000 | 125420.0 | 99400.0 | -20.7% |
| default | optimization | three-route-mixed | 284626.0 | 289674.0 | +1.8% |
| default | optimization | ordinary-bulk-extract | 1046.7 | 1101.8 | +5.3% |
| synchronous | batch | native-one-slot | 259.0 | 253.6 | -2.1% |
| synchronous | batch | native-nine-repeated | 1706.8 | 1709.4 | +0.2% |
| synchronous | batch | native-nine-distinct | 2534.0 | 2495.1 | -1.5% |
| synchronous | batch | native-nine-reserved | 2573.6 | 2415.8 | -6.1% |
| synchronous | batch | native-shared-seed | 2643.1 | 2678.6 | +1.3% |
| synchronous | batch | legacy-nine-repeated | 1060.4 | 1046.4 | -1.3% |
| synchronous | batch | legacy-nine-distinct | 1170.3 | 1226.1 | +4.8% |

新原始 [JSONL](benchmarks/core-algorithm-20261102/final-raw.jsonl) 的 Git UTF-8 LF SHA 为：
`79b40e5cc91bc16497db2cbf827821390db0bad91ee024810a1ac6e11b4b850b`。
完整三 JVM 数组、分配、首次调用、GC、依赖及输入清单在[机器记录](baselines/core-algorithm-release-20261102.json)。

默认组 legacy-nine-distinct 仍有 **+51.6%**，独立同步组同例 **+4.8%**、共享种子
**+1.3%**；同步组七例分配量相同。JIT 阶段与后台负载影响仍在，部分主机 CPU
百分比缺失，后台只被观测而未严格控制。**不宣称稳定提速或无回退。** 原第⑤步
18 JVM 样本及其 +159.6% / +15.1% 等信号保留原身份，不与本次新 18 JVM 合并。

## 第⑦步结束时的跨项目待验范围

以下保留第⑦步交付时的待验范围。后续 A6 契约补充、LT 第⑧步及直接联测结果见
[2026-11-03 跨项目汇总](GTO_CORE_JOINT_ACCEPTANCE_20261103.zh-CN.md)，其中也保留了
LT 独立 Core 定向测试首轮的一次取消监视超时及原预算诊断复跑结果。

Core ③–⑦的源码、独立审查和本地制品检查已形成交付。LT 仍须对以上同一 SHA
执行第⑧步 A1–A9：双版本实际 Mixin/API、规划 UNKNOWN/截止取消、闭环物理种子、
真实批量回执、外部拒收/部分退款/异常及不确定状态的唯一保管者、Dev25 exact、
每版三个独立 JVM 保存恢复、真实 FX 和受影响的图形/多人增量。

内部 `ListCraftingInventory.insert` 的 void 调用不表达外部拒收或部分退款回执。
本次内部守恒及服务端 EJECT 通过不构成这些外部场景通过；若 LT 实际发现协议无法
保留明确归属，须反馈具体缺口并按第①步集中修订契约。LT 结论返回后再做第⑨步
跨项目汇总，历史 `.3/.4` 的 FX、保存恢复和图形记录不移记为 `.5` 新验收。
