# Core 算法续办：第①步基线、契约与文件归属

本文件承接已停止任务 `d71c7c27-e17f-45ff-9faf-55171bcc01b4`，用于流程
「Core算法续办与制品验收」的第①步。当前任务卡为
`0bb5b34d-8d3e-4ccd-8210-15e3fc33ff0a`。原任务讨论串只有接手回复，不能据此
认定算法修复或新制品验收已经完成。用户在只读核对及方案交付后明确回复“可以，实施”。

本步交付本文件和[机器可读基线](baselines/core-algorithm-gto4-20261102.json)，不改变
生产源码、公开签名、依赖配置或版本号。以下行为语义是后续核查的验收约束，
不是对 `.4` 所有执行路径已满足这些条件的证明。

## 1. 恢复点与证据身份

| 项目 | 固定值或边界 |
| --- | --- |
| 工作区 | `E:/mods/Thunderbolt-Core-GTO`，仅操作本项目 |
| 本地交接分支 | `1.20.1_GTL` |
| 未改动性能 before / 源码基线 | `bd510967eb1f9a376d80e74695bef27bafbaa5dd` |
| 当前版本 | `2.0.4-gto.4` |
| 入口工作树 | 已跟踪文件无改动；仅有未跟踪 `AGENTS.md`，保留原样且不提交 |
| 平台 | Minecraft `1.20.1`、Forge `47.4.20`、Java `21` |
| 当前 Java | Microsoft OpenJDK `21.0.10+7-LTS`；实际测试 JVM 仍需各轮记录 |
| 构建工具 | 现有 Gradle `8.14` wrapper，official `1.20.1` mappings |
| AE2 实际依赖 | AE2-GTO `15.2610.31` 与 `15.2610.40`，分别验证 |
| 保留能力 | `.4` EJECT / ElementalCraft 修复、双 AE2 ABI、完整键身份和既有批量结算 |
| 对照范围 | 普通 `1.20.1`；`main` / `alpha` 只作跨版本参考；忽略 GTL 上游 |

`.4` 正式文件为 `thunderbolt-forge-1.20.1-2.0.4-gto.4.jar`，5,471,709 字节：

```text
Maven: com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1:2.0.4-gto.4
SHA-256: 734d9bf152a66dee5bf69397106b6808a64305969459ba60c2e8f7139c08c7cd
```

本步直接核对 `build/libs/` 与项目 `repo/` 的同版包，并保存以哈希命名的本地对照副本、
该提交的完整 tracked 源码压缩 tar 和两个基准入口的原始 Git 内容，位置详见 JSON。
这些副本位于 `.gradle/core-algorithm-20261102/`，已存在的同名证据只核验、不覆盖。
它们是本地可追溯输入，不是公开不可变下载服务；第⑦步另交付新制品的不可变获取位置。

历史报告[`.4` EJECT](GTO_EJECT_ELEMENTALCRAFT_PORT_20261102.zh-CN.md)和
[`.3` 双 ABI](GTO_DUAL_AE2_ABI_FIX_20261031.zh-CN.md)保留原结论及其限制。
LT 草稿 PR #54 固定提交 `6135dcc2110d3b745682b854ce013e10c22583d5` 的 CI、
51/51 GameTest、两版 FX、每版三个独立 JVM 保存恢复、10/10 图形、4/4 多人，
属于频道约定的历史证据，固定的是 Core `.3`：

```text
1ebff68ab9a2c7e041b8ca8338acb1a00aa9f5eacfa6604b4f55f2cd04be85a9
```

这些旧结果不计为 `.4` 或新制品通过。本步没有重新运行这些矩阵。

AE2 两版依赖身份沿用 `.3` 双 ABI 报告的正式输入哈希：

| AE2 | 历史正式输入 SHA-256 |
| --- | --- |
| `15.2610.31` | `f63917bf5a2ca0692c0550ad2d045991f7a493d89021bfb30b51026d4ad31a3d` |
| `15.2610.40` | `b0d97544e618d73ec0c8d8e54df36351bf7f98f7e1984c6455d81658b6320b5c` |

本步额外尝试的 AE2 重新取包未完成，已停止本任务取证进程，没有完整新下载或实际 JAR
核验结论；JSON 标记为 `historical_report_only`。第⑦步必须取得真实正式依赖、重新核对
输入哈希并实际运行，不能把本表、源码编译或本步清单核验当作新制品的双 ABI 通过。

## 2. 固定参考及许可证边界

NeoECO AE Extension（DancingSnow、ZhuRuoLing、Yang120231、y271727uy）只用于
算法与行为参考，来源固定为：

```text
Repository: https://github.com/Yang120231/NeoECOAEExtension
Commit: 381e3f844beca250b0f824017f0fe585c9364144
Tree: 6f1bd9db8ab55ae7fbc50f370a6c60f5027b5ed8
License: GPLv3
LICENSE Git blob: e72bfddabc15be5718a7cc061ac10e47741d8219
LICENSE SHA-256: 8b1ba204bb69a0ade2bfcf65ef294a920f6bb361b317dba43c7ef29d96332b9b
```

[固定源码](https://github.com/Yang120231/NeoECOAEExtension/tree/381e3f844beca250b0f824017f0fe585c9364144)
的属性版本为 `20.5.5-gto.1`、Forge `47.4.28`。后者只描述参考项目，不能替代本项目
Forge `47.4.20` 验证。保留 Core 的 LGPL 3.0、`THIRD_PARTY_NOTICES.md` 及必要署名；
不复制 GPL 实现、测试或夹具到 Core。独立修复必须由 Core 缺口复现、行为约束、
独立 oracle 和库存回放支撑，不能把相似实现本身当作正确性证据。

旧 `.gradle/neoeco-gto-reference/tree.json` 的 tree 是
`04fde3db6ce85eeb11b289d53c0f656ee39a2183`，不是本轮固定 tree。
其 18 个缓存源码文件中，17 个与固定提交匹配，`build.gradle` 不匹配。
旧缓存不修改；本步在独立忽略目录保存固定 commit/tree 和所需源码子集、原许可证，
逐文件核对 Git blob 和 SHA-256。完整 tree 已保存，源码只下载清单中的子集，
后续需读其他文件时必须按该固定提交取回并校验，不能使用移动分支或未校验缓存。

按当前要求继续排除 `neoecoae`，不得新增或恢复其适配、注册、依赖、配方及测试夹具。
`.4` 已存在的 `compat/neoeco/NeoEcoGtoBatchAdapter` 和 `OptionalBatchProviders` 的
条件发现是本次的既有边界：本步冻结，不扩大也不删除。第⑤步仅可依据可复现缺口
独立修正既有桥实现；外部桥与 LT 配套结论由第②/⑧步交付，不越界修改其他项目。

## 3. 共用状态、预算与线程契约

默认使用现有 `api.crafting`、`api.crafting.batch` 和版本匹配的 `core.crafting.loop`
契约，不新增 LT 必需接口、不改变公开签名，也不新增公共 UNKNOWN 枚举。
JSON 的 `frozen_shared_sources` 是逐文件冻结清单；哈希针对 `git show <baseline>:<path>`
的原始字节，不受 Windows checkout 换行转换影响。

本步冻结 82 个生产/共享测试源文件，并从同一 `.4` 正式 JAR 用 Java 21 `javap` 导出
对应生产类型及嵌套类的公开描述符，共 65 个 class。清单记录每个 class 及描述符输出的
SHA-256；这是声明成员的静态签名快照，不包含继承方法发现，也不是实际链接或双 ABI 测试。

### 3.1 规划结果

| 现有表达 | 本轮锁定语义 |
| --- | --- |
| `PlanningAttempt(HANDLED, plan, ...)`，plan 非空 | 此数量探测有可执行见证；成功必须经过全局库存与执行顺序认证，不自动表示最优 |
| `PlanningAttempt(HANDLED, null, simulationFallback)` | 权威无解结论；可带模拟回退，不得由截断、UNKNOWN 或取消生成 |
| `PlanningAttempt.DECLINE` | 候选不作确定结论；包含不支持或未确定情形，按路由契约处理，不等价于确定无解 |
| `PlanningExitException` / 外层取消 | 停止计算的信号；监控层保留预算或取消原因，不能吞掉后继续无界搜索 |
| 内部错误、软/硬超时、隔离状态 | 保留诊断，分别记录；不得用空计划伪装成确定无解 |

已找到且在预算内完成认证的见证可按现有契约保留为可行解，不得宣称最优。
`finish` 只附加同一计算的元数据；`close` 在成功、拒绝、异常和取消后释放候选状态。
成功或确定无解缓存只能接收对应的有效证明；UNKNOWN、预算耗尽、取消和内部错误
不能写入这些缓存。缓存还需区分配方 epoch、库存版本、请求约束及键身份。

### 3.2 deadline 与取消

`PlanningAttemptContext.deadlineNanos()` 是 `System.nanoTime()` 时钟的绝对截止点；
`checkpoint()` 承接调用方截止与取消。预检、模型准备、搜索、可执行认证、结果装饰和
回退都受现有调用预算约束，检查点应覆盖长循环和原生求解边界。

嵌套阶段只能收紧剩余预算；不能重置 deadline、重新给预算、忽略取消，或转入另一候选
来绕过调用方截止。若路由原有候选各有局部预算，也必须尊重已经存在的外层截止。
纯计算 CPU 配额不能取消外层墙钟截止或中断。取消必须有界退出；预检、认证与冷启动
耗时都计入适用预算。第③步用实际调用链和回归验证，不因本步文档锁定而认定实现已通过。

`CraftingPlanningEngine.check/capture` 在 Grid 调用线程有界、非阻塞，worker 只消费
允许的捕获快照；`PlanningEngineSession` 限于单候选线程。不得把活动 Grid、借用库存
或可变订单对象跨线程保留为新的隐含接口。

## 4. 循环、材料键与执行元数据

- 小图覆盖无种子循环、多环竞争同一物理种子、催化剂返还/副产物、已有中间库存、
  同 ID 不同 NBT/身份键和大数量。独立 oracle 的图规模必须可完成，不能共享被测
  规划器的核心判定；成功计划按真实顺序回放，每一步取料均需可用。
- `ReusableStockSource(storageScope, poolScope, routingScope)` 区分物理库存、逻辑池及路由。
  安全单种子环的共享池不产生额外物料；多种子环的独立池仍竞争同一物理库存。
  `ReusableSeedPattern` 的默认方法、组 UUID 和库存快照语义冻结，不能双重计入种子。
- `IPlannedSeedSlotPattern` 标明执行槽选择的实际种子；`ISeedPreservingCraftingTask`
  保留组与循环键；`IPrioritizedCraftingTask` 保留安全派发顺序。
  `CraftingTaskPersistenceDefinition` 保留可解码的持久化定义。接口存在不替代执行验证。
- `IProviderLookupPattern` 的查找解包与 `IBatchExecutionPattern` 的执行解包分开，
  不能丢失影响物理输入、输出、容量、共享种子和持久化的包装层。
- `AEKey` 以实际 AE2 fork 的完整身份为准，不能只按注册 ID 合并严格变体。
  `IdOnlyInputGroups` / `LateBoundOutputKey` 保留 `.40` final 方法兼容、每图逻辑节点复用、
  严格键限制及执行/序列化前投影；不新增违反 equals/hashCode 的逻辑键。
- `BatchKeyAmounts` 保留共同父类擦除 long 调用的双 ABI 约束，不恢复旧版专用描述符，
  不靠改 AE2 原件、去掉 final 方法或合成接口得出兼容结论。

## 5. 批量回执、费用与唯一保管者

`IBatchCraftingProvider.pushBatch` 和 `PreparedBatch.push` 返回**未接收份数**。
对 offer 必须验证 `0 <= leftover <= offered`，实际接收量为 `offered - leftover`。
`getBatchCapacity` 仅为建议上界，真实提交回执才有结算权。

`oneCopyTemplate` 和 `BatchJobView` 是本次调用借用的只读模板/活动视图，不得保留。
延迟或准备阶段需要的数据必须复制，并在 prepare 固定真实订单 UUID 及必要身份；
不能在 submit 时从已切换的 view 读取另一订单。`PreparedBatch` 仅在当前派发调用有效、
只提交一次，不跨 tick 缓存；缩小 offer 后应按协议重验。

| 情形 | 所有权与结算约束 |
| --- | --- |
| simulate / prepare | 无取料、转移、扣费或外部提交副作用；prepare 不取得材料所有权 |
| 完全拒收，且确认无副作用 | 调用方保管全部未接受材料；仅在协议允许时退款或走普通回退 |
| 部分接收 | 接收方保管已接受部分；调用方只结算并处置未接受部分 |
| 取料不足 | 按实际取出量重验/缩量或保留退款责任，不能按计划量提交或记账 |
| 退款拒收/不足 | 未回存部分继续有明确唯一保管者，不能丢弃、重复掉落或重复退款 |
| 异常且协议明确保证回滚 | 按该真实保证处置；不能泛化为所有异常均无副作用 |
| UNKNOWN / 接受状态未知 / 回执越界 | 停止派发并保留归属与诊断，不盲目退款、普通回退或重提 |

材料、能量、费用、任务余量与等待产物按真实回执仅扣记一次。共享输入支持只表示能力，
不授予材料所有权、持久化责任或重复收费权限。原生 provider 优先；缓存按 provider 身份
和既有生命周期作用域，不保留 job-bound wrapper，不缓存机器容量或接收结果。

既有 NeoECO allocated-input 桥保持 CPU 负责一次取料/能量结算、外部准备无再次收费、
默认回调、UUID 普通回退和不确定提交传播。对接口无法表达的退款回执或未知状态，
必须报告具体缺口；若确需共享接口变化，先退回第①步再验证，不能凭空假定回存成功。

第⑤步以全局守恒和唯一保管者为主要断言：

```text
初始材料 + 明确外部输入 + 规则允许生成
  = 存储余额 + 调用方持有 + 接收方保管 + 已交付/已掉落 + 规则允许消耗
```

催化剂、返还物、费用和能量分别记账，不能重复计入。涉及待退款/已接受工作的持久化时，
第⑧步必须追加真实 JVM 保存退出后恢复，不以同进程对象重建代替。

### 5.1 A6 补充：外部回存已部分生效但没有回执

LT 第⑧步实测了 `BigStorageOps.insert` 的接收端写入 2/10 后抛异常，旧 escrow 仍
保存 10、恢复后自动重试而使外部余额成为 12 的路径。上表的 UNKNOWN、唯一保管者
与不得盲退/重提条款已经涵盖该情况。现补充[外部退款隔离契约](GTO_CORE_REFUND_UNKNOWN_CONTRACT_20261102.zh-CN.md)
及[机器记录](baselines/core-refund-unknown-contract-20261102.json)，明确 LT 的持久化和清理边界。

**可沿用现有 Core 公开接口，由 LT 实现本地未确定退款隔离；本项不要求改变 Core
`.5` 二进制、共享类型或方法签名。** `BigStorageOps.insert` 正常返回的是已接收量，
不同于批量派发的未接收份数；无回执异常不能推导为零接收或完整回滚。
隔离记录保存一次尝试的风险敞口及唯一对账责任，不是额外可用库存，也不宣称调用方
仍实际持有全额。LT 须阻止同一次未确定转移在 tick、恢复、取消、完成清理、拆除或
重连时被再次退款、重新用于合成或物化；未确定记录必须在唯一持久 owner 下保留。

该结论允许安全暂停并等待权威对账，不授予现有 `MEStorage` 自动恢复或跨存储的
exactly-once 保证。有序保存/恢复与任意进程崩溃的持久性边界分别验证；若要求无回执
后的自动重试，须先有接收端参与的持久幂等/可查询回执契约，再回①集中评审。
本补充不把 LT A6 记为通过，不覆盖历史失败或已固定的 `.5` 制品。

## 6. 文件归属与顺序门槛

所有路径相对仓库根目录；Java 路径省略公共前缀 `src/main/java/com/moakiee/thunderbolt/`。
**第①步逐文件冻结清单优先于目录归属。** 不在明确允许范围内的跨域变更先回第①步
补充归属与契约，再继续后继。新增仅服务单专项的私有类型/测试辅助可归对应专项；
新增共用类型、公共接口、共享夹具必须由第①步集中处理。

| 步骤/成员 | 写入范围与交付 |
| --- | --- |
| ① Core | 本文及 JSON；独占 `api/crafting/**`（含 batch/cpu）、`core/crafting/loop/**`（现有文件均为契约）、`core/crafting/pattern/**`（含 `PlannedInputPattern`、`ReusableStockSource`）、`core/crafting/overload/**`、`compat/gto/inv/**`、batch 下两个共享接口。默认全冻结，源码不改。共享 API 契约测试、reference kit、Minecraft bootstrap、GTO test agent 及包边界测试也在 JSON 冻结清单中 |
| ② LT | 只核对 LT 固定提交的 Core 调用、外部桥契约与增量验收用例；产出是否可沿用接口的基线。Core 不读取或修改其他项目目录 |
| ③ Core | `core/crafting/planner/**`、`core/crafting/plan/**` 的实现；规划入口限 `compat/gto/GtoPlanningCalculation.java`、`mixin/compat/gto/GtoCraftingPlannerMixin.java`、`ae2/crafting/CraftingPlanningControl.java`，以及 `core/crafting/algorithm` 中规划服务/候选执行/预算监控的实现。保持公开签名，不改菜单/客户端。对应 `src/test/.../core/planner/**`、`core/crafting/planner/**`、`core/crafting/plan/**`、规划服务测试；排除冻结 reference kit。专项私有 oracle/库存回放在本步新增 |
| ④ 审查 | 全程只读审查③的认证、种子守恒、预算和 LT 契约；给 pass/reject，reject 必须有具体问题并退回③ |
| ⑤ Core | `core/crafting/batch/**` 实现（排除两个冻结接口）、`compat/neoeco/NeoEcoGtoBatchAdapter.java` 的边界内修复；对应 `src/test/.../core/crafting/batch/**`、`ae2/batch/**` 测试；`src/test/.../core/OptimizationBenchmark.java`、`core/crafting/batch/BatchAllocationBenchmark.java` 及新固定输入/基准证据。其他适配器、注册与跨域 Mixin 不默认开放 |
| ⑥ 审查 | 全程只读审查⑤的回执/退款/UNKNOWN、双 ABI 和基准有效性；reject 具体退回⑤ |
| ⑦ Core | 集成、`build.gradle`/版本/发行元数据、相关 GameTest、发行报告及证据。汇总功能为单个本地提交，核验后只更新既有 PR #17 头和描述；源码修正仍按归属退回并重审 |
| ⑧ LT | 在 LT 自己的授权范围固定⑦同一正式包及哈希，运行双 AE2 增量验收和必要保存恢复；交付公开接口及外部桥结论 |
| ⑨ 任务分配 | 汇总核查、缺口依据、原始基准、验证范围、制品与 PR、LT 配套责任，不改被验收源码 |

第②步核对完成后，若发现必要接口变化，退回①修订再启动受影响步骤。
③与⑤不同时写入；③完成后，④可与只修改批量文件的⑤并行。④拒绝时⑤可继续独立核查，
但不得依赖被拒绝的规划结论；③修正若涉及共享类型或⑤输入，则暂停相关写入并先回①。
⑦以④和⑥通过为门槛，⑧依赖⑦冻结，⑨依赖⑧结论。审查员不代改 target。

`LoopCraftingPlan` 是 Core 默认实现而非新增稳定 API，沿用
[既有职责边界](thunderbolt-ae2-planner-boundary.zh-CN.md)。其第③步实现修正不能
改变外部调用形状或借机迁移 LT 数据格式；必要的共享变化仍先退回①。

## 7. 基准与正式包验收门槛

以未改动 `.4` 为主要 before，复用 `optimizationBenchmark` 和 `batchAllocationBenchmark`
固定输入，原始入口 Git blob/SHA-256、场景、循环规模和库存数量写入 JSON。
原场景均为确定性构造，无随机种子；新增场景在⑤固定种子、规模和输入文件，并让
before/after 使用同一套测量入口。不能用当前修改后的类目录冒充 `.4`。

两侧相同硬件、Java、堆、依赖、调用预算与后台负载，每侧至少三个独立 JVM；冷启动和
预热分别报告耗时、分配字节、GC、原始样本及实际载入实现哈希/来源。
现有两个入口只输出预热后的聚合数据，缺少完整冷启动、逐样本和 GC 证据；⑤需补测量，
不能从已有汇总 JSON 推导这些指标。复用基线类目录时检查实际类加载，不能只看 classpath 顺序。
性能修改先有可重复热点，再报告测得改善；不预设提速比例，不调高预算或删断言制造通过。

⑦必须运行现有 wrapper 的 `check`、`referenceCapabilityTest`、两套真实 AE2 的
`ae2AbiTest`、`verifyReleaseJar`，并完成相关 GameTest、双环境 EJECT/ElementalCraft 与
正式包加载。已有任务的基本入口是：

```powershell
.\gradlew.bat check referenceCapabilityTest verifyReleaseJar
.\gradlew.bat ae2AbiTest '-PthunderboltAe2AbiJar=<固定 .31 正式 AE2 JAR>' --rerun-tasks
.\gradlew.bat ae2AbiTest '-PthunderboltAe2AbiJar=<固定 .40 正式 AE2 JAR>' --rerun-tasks
```

每套 ABI 结果立即另存，避免同名任务输出覆盖；保留实际依赖哈希和 Core 类来源。
ABI 任务的开发映射依赖只转换 Minecraft 名称；它仍不替代同一个最终完整重混淆 Core JAR
在两套正式加载器环境的加载和行为证据。开发映射副本与正式包分开标注输入、转换及结果。
正式包核查 Mixin、唯一 refmap、嵌套依赖、许可证、署名、无 QA 夹具混入；
`verifyReleaseJar` 的通过不能独自替代全部内容与运行时检查。

每项分别记为通过、失败、跳过、未执行，并保存原始失败与对应制品身份。
本步只验证基线/清单/引用，不运行 Gradle、GameTest、正式加载或性能比较。

## 8. 发行、提交与 LT 配套结论

新坐标候选为
`com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1:2.0.4-gto.5`。
本步不分配或发布该版本；⑦核对已发布坐标，若占用，取更大的首个未发布 gto 序号。
禁止覆盖任何旧坐标或 JAR。⑦交付文件名、不可变获取位置、源码汇总提交、完整 Maven
坐标、SHA-256、环境/依赖清单，并确认验证输入与交付包逐字节一致。

[Core PR #17](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/pull/17)
本步查询为 OPEN，base 为 `gto`，实际 head 为
`pr/core-2.0.4-gto.3@2670dc3f9fff738b2416ba5d14fdb2a4575b4d17`。
远端 `gto` 是既有 PR 目标，不应误作本步要推送的 PR 头。⑦操作前重新核验 PR 仓库、
头分支和提交；仅更新核验过的现有头与描述，不新建/上传开发分支，不自动推标签，
不使用 `--mirror` 或 `--all`。

本步按流程要求仅暂存、提交自己的两份文档到本地 `1.20.1_GTL`，使后继可从当前提交
分出工作。此为本地交接提交；⑦再将整项已验证功能整理为一个汇总提交合入同分支，
不把本地过程历史上传。保留既有工作，始终不修改或提交 `AGENTS.md`。

⑧必须固定⑦同一正式包/哈希，在两版 AE2 复验涉及的规划/种子、批量结算、既有桥契约。
持久化或待退款变化需真实保存退出后恢复；图形、FX、多人按变更影响选择增量，
不重做 LT 已完成的准备快照/订单身份、默认回调、JEMI 工作，也不机械重跑全部旧矩阵。
结论须明确“现有公开接口与外部桥无需变化”，或列具体不兼容、最小配套变化与责任方；
尚需配套的组合不算通过。源码或制品后续改变，使受影响审查、基准和验收证据失效。

第⑨步汇总以上证据、实际缺口与独立修复依据、所有通过/失败/跳过/未执行、新坐标、
源码提交、发行 SHA-256、PR #17、LT 配套结论和未闭环责任，不把本步契约当作运行结果。
