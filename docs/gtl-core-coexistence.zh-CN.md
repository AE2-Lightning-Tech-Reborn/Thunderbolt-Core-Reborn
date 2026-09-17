# 与 GTLCore（GTL）共存：规划器接入与 CPU 派发让位

## 文档状态

- 状态：已在本地工作树实现；**尚未在 GTL 整合包内完整启动一次**（原因见"实机验证的进展与阻塞"），因此未合入 `main`。
- 适用模块：`compat/gtl/GtlCompat`、`mixin/OptionalMixinSelector`、`mixin/ThunderboltMixinConfigPlugin`、`ThunderboltCore`、`mixin/ae2/crafting/CraftingCalculationMixin`、三个访问器与 `ExtendedCraftingCpuServiceMixin`。
- 结论：GTL 环境下 Thunderbolt 让出 **CPU 派发**，规划器挂在 `CraftingCalculation#computePlan`（GTLCore 覆盖后的 `run()` 仍调用它），其余子系统（频道最大流、弹出、索引存储、扩展合成 CPU、客户端界面、对 AE2LT 暴露的 API）保持启用。
- 本文只描述 Thunderbolt 自身的适配，不改变 GTLCore 的任何行为。接入方式参考 [NeoECOAEExtension `gtl-port`](https://github.com/Yang120231/NeoECOAEExtension/tree/gtl-port)：不与 GTLCore 的 `@Overwrite` 对打，在覆盖后仍存活的入口接入。

## 为什么 CPU 派发必须让位，规划器可以接入

GTLCore 用 `@Overwrite` 接管了 AE2 合成计算与 CPU 派发的核心方法：

| GTLCore mixin | 目标 | 方式 |
| --- | --- | --- |
| `org.gtlcore.gtlcore.mixin.ae2.crafting.CraftingCalculationMixin` | `CraftingCalculation` | `@Overwrite run()` / `finish()` / `simulateFor(int)` |
| `org.gtlcore.gtlcore.mixin.ae2.logic.CraftingCpuLogicMixin` | `CraftingCpuLogic` | `@Overwrite tickCraftingLogic(...)` / `executeCrafting(...)`，`priority = 1100` |

`@Overwrite` 替换整个方法体。Thunderbolt 若把注入锚在被覆盖方法**内部的调用点**上，两种应用顺序都不成立：

1. **GTLCore 先应用**：调用点消失，`defaultRequire: 1` 使注入失败成为类加载期硬失败。
2. **Thunderbolt 先应用**：随后的 `@Overwrite` 把已注入的方法体丢掉。

CPU 派发没有存活的替代入口：GTLCore 自己拥有 `executeCrafting`，整合包靠它做样板展开（催化剂、自动 ME 样板膨胀）。这批 mixin 必须让位。

规划器不同。GTLCore 覆盖后的 `run()` **仍然调用** AE2 原来的私有方法 `computePlan()`。Thunderbolt 用 MixinExtras `@WrapMethod` 包住这个方法本身，因此无论 `run()` 的调用点如何改写，规划引擎都在 GTLCore 的计算任务里运行（MAX_FAST 指标、日志、`finish()`）。Thunderbolt 回退到原版规划器时走 `original.call()`，仍落在 GTLCore 对 `CraftingTreeNode.request` 的 Redirect 上。

GTLCore 的 `simulateFor(int)` 是空操作（`return !done`）。AE2 的每刻 monitor 协议（`running = false; monitor.wait()` 直到下一次 `simulateFor`）在 GTL 下会永久阻塞。候选隔离因此在 handover 生效时改用 `LockSupport.parkNanos(1_000_000L)` 轮询。

## 接入与让位边界

| 交出（GTL 环境下不再应用） | 保留 |
| --- | --- |
| `CraftingCpuLogicBatchMixin`（AE2 原生 CPU 的批量派发与账目） | `CraftingCalculationMixin`（`@WrapMethod computePlan`、候选执行、循环合成计划包装） |
| `AdvCraftingCpuLogicBatchMixin`（AdvancedAE CPU 批量派发） | 频道最大流（`GridNodeMaxChannelsMixin` 等） |
| `ExtendedAePlusSuperMatrixBatchMixin`（ExtendedAE Plus 超级矩阵批量派发） | 弹出（`Eject*`）、索引存储（`IndexedStorageCellHandler`） |
| `AppliedETransmutationModuleBatchMixin`（AppliedE 转化模块批量派发） | 扩展合成 CPU 集群（`ExtendedCraftingCpuServiceMixin`） |
| | 客户端界面（`CraftConfirmScreen*`、`CraftConfirmMenuMixin`、`CPUSelectionListStorageMixin`、`Tooltips*`） |
| | 对 AE2LT 暴露的 API（`api/**`、`CraftingPlanningEngines` 注册表、菜单与方块实体） |

批量派发家族整体让位而不是逐个判断：它们的账目按 AE2 样板语义推导材料消耗，而 GTL CPU 按 GTLCore 语义展开样板（催化剂槽、自动膨胀）。把 GTL 计划交给 Thunderbolt 的批量账目去扣，会扣错键。

`CraftingPlanningEngines` 仍注册 `ThunderboltV2PlanningEngine`（优先级 1000）。GTL 环境下该选择不再是惰性的：`CraftingCalculationMixin` 保持应用，`beginCraftingCalculation` 会把候选写入 `CraftingPlanningControl`，CP-SAT 原生运行时也会按配置初始化。引擎成功则返回 Thunderbolt 计划（可能包成 `LoopCraftingPlan`）；全部失败则回退到 GTL / AE2 原版树。

`LoopCraftingPlan` 实现 `ICraftingPlan` 并包装 `CraftingPlan`。原版 / GTL CPU 不一定识别 Thunderbolt 的包装样板（`ReusableSeedPattern`、`CraftingCpuRestrictedPattern`），扩展 CPU 可以。提交策略：优先交给扩展 CPU；没有合适的扩展 CPU 且 handover 生效时，把剩余 `ICraftingPlan` 留给 GTLCore，而不是硬返回 `CPU_OFFLINE`。

## 开关与诊断

模式来自系统属性，因为 CPU 派发让位决策发生在 Mixin 应用阶段，此时 Forge 配置尚未读取、游戏类不可加载：

```
-Dthunderbolt.gtlCompat=auto    默认：仅当 gtlcore 存在时让出 CPU 派发
-Dthunderbolt.gtlCompat=always  即使没装 gtlcore 也让出 CPU 派发（用于在普通实例上复现 GTL 行为）
-Dthunderbolt.gtlCompat=never   装了 gtlcore 也不让出 CPU 派发（保留 Thunderbolt 的 CPU 批量钩子）
```

规划器不受该开关抑制：`CraftingCalculationMixin` 始终应用。开关仍改变规划器的让步方式——handover 生效时用 1 ms park，否则走 AE2 monitor。

- 判定逻辑集中在 `GtlCompat`（纯函数），Mixin 阶段只读系统属性与调用方给出的"模组是否存在"谓词；GTLCore 是否存在在 Mixin 阶段由 `LoadingModList` 判定（`ModList` 那时还没填充）。运行期 `isGtlPresent()` 与插件同一策略：`LoadingModList` **命中**即视为在场，未命中则再问 `ModList`；只有 `ModList` 给出确定的否才缓存 `false`。探测尚不确定时返回 `false` **但不缓存**，避免 mixin 已让出 CPU 派发、运行期却按 AE2 monitor 等待（GTL 的 `simulateFor` 不会唤醒）或把剩余 `ICraftingPlan` 打成 `CPU_OFFLINE`。
- CPU 派发让位生效时，Mixin 阶段每个被抑制的 mixin 打印一条 INFO，游戏启动时再打印一条汇总。
- 使用 `never` 而 gtlcore 存在时打印 WARN，明确提示可能出现注入失败或派发冲突。
- GTL 环境下仍按配置准备 CP-SAT 原生运行时（规划器不再惰性）。

## 访问器命名空间

两个模组在**同一批 AE2 目标类**上定义了同名的 `@Accessor` / `@Invoker` 成员：

| AE2 目标 | Thunderbolt（原名） | GTLCore |
| --- | --- | --- |
| `ExecutingCraftingJob` | `getTasks`、`getWaitingFor`、`getTimeTracker`、`getLink` | 同名 |
| `ExecutingCraftingJob$TaskProgress` | `getValue`、`setValue` | 同名 |
| `ElapsedTimeTracker` | `invokeAddMaxItems`、`invokeDecrementItems` | 同名 |

这不是崩溃点：Mixin 0.8.5 的 `MixinApplicatorStandard.mergeMethod` 在遇到同名同描述符的方法时，会移除先前定义并保留后应用的版本（`@Final` / `requireOverwriteAnnotations` 之外不报错）。也就是说**归属由 mixin 应用顺序决定**，一旦两侧的字段名或描述符日后出现分歧，就会静默地由后应用的一方胜出，且没有提示。

因此 Thunderbolt 侧的这 8 个成员改为 `thunderbolt$` 前缀：

| 文件 | 新名字 |
| --- | --- |
| `ExecutingCraftingJobAccessor` | `thunderbolt$getWaitingFor`、`thunderbolt$getTimeTracker`、`thunderbolt$getFinalOutput`、`thunderbolt$getRemainingAmount`、`thunderbolt$setRemainingAmount`、`thunderbolt$getLink`、`thunderbolt$getTasks` |
| `TaskProgressAccessor` | `thunderbolt$getValue`、`thunderbolt$setValue` |
| `ElapsedTimeTrackerAccessor` | `thunderbolt$invokeDecrementItems`、`thunderbolt$invokeAddMaxItems` |

- `@Accessor` / `@Invoker` 的目标字段/方法由注解的 `value` 给出（GTLCore 用的是 `@Invoker(remap = false)` 隐式形式），Java 方法名与方法种类无关：getter/setter 由描述符推导（返回 `void` 且 1 个参数即 setter），因此改名安全。
- 调用点集中在 `CraftingCpuLogicBatchMixin`，已同步更新。
- `CraftingCpuLogicAccessor` 的 `getJob` / `invokeFinishJob` / `invokePostChange` 当前与 GTLCore 的 `@Shadow` / `gtlcore$invokePostChange` 不重名，仍改为 `thunderbolt$` 前缀，避免日后 GTLCore 补同名 accessor 时 Mixin 0.8.5 静默覆盖。`CraftingServiceAccessor` 原本已带前缀。
- AE2LT 不受影响：它**自带**一套 `com.moakiee.ae2lt.mixin.thunderbolt.accessor`（`ae2lt$` 前缀）绑同一批 AE2 字段，并且 `ThunderboltMixinBoundaryTest` 禁止其源码引用 `com.moakiee.thunderbolt.mixin` 包。

## 已核对为无冲突的交叉点

以下类被两个模组同时修改，逐项核对后确认不需要让位：

- `CraftConfirmMenu` / `CraftConfirmScreen`：注入点不同。算法被选中时走 Thunderbolt 摘要；未选中时回退到 AE2 原有摘要路径。
- `CraftingPlanSummary`：`CraftingPlanSummaryAdapter.adapt()` 对 `CraftingPlan` 返回同一对象，GTLCore 的注入读到的是兼容数据。
- `CraftingService`、`GridNode`、`Level`、`CPUSelectionList`、`Tooltips`、`ExecutingCraftingJob`（GTLCore 的 `ExecutingCraftingJobMixin`）：除 `submitJob` 的 `findSuitableCraftingCPU` `INVOKE_ASSIGN` 外，注入方法互不重叠。该同点注入上 Thunderbolt 在 `cir.isCancelled()` 时退出，让 GTLCore 已经选定的超限 CPU 不被覆盖；GTLCore 未接管时仍可由扩展 CPU 接手。`CPUSelectionList.formatStorage` 双方都在 HEAD 可取消注入：Thunderbolt 的 mixin `priority = 1100`，只在容量为 `Long.MAX_VALUE` 时写成 `∞`，有限容量仍走 GTLCore 的紧凑数字格式。
- `ExtendedCraftingCpuServiceMixin.thunderbolt$configurePlanningSelection`：始终把 `CraftingCalculation` 转成 `CraftingPlanningControl`（该接口由保持应用的 `CraftingCalculationMixin` 提供）。非 `CraftingPlan` 的 `submitJob` HEAD：显式目标在 handover 下直接返回，把未知计划类型留给 GTLCore；自动路径先尝试扩展 CPU，没有合适目标且 handover 生效时同样返回而不是 `CPU_OFFLINE`。
- `CraftingCalculation`：Thunderbolt 不再注入被覆盖的 `run()`。`@WrapMethod computePlan` 包的是方法本身，GTLCore 覆盖后的 `run()` / `gTLCore$computeMaxFastPlan()` 调用它时都会进入包装。`runCraftAttempt` / `handlePausing` 上的 HEAD 注入仍然存在：原版回退路径会置 `thunderbolt$activeVanilla`，引擎候选路径用隔离线程，不会把 `PlanningCandidateDeclinedException` 抛进 GTLCore 的规划器。

## 回归验证

静态核对（对照 GTLCore 源码树，输出为空即无冲突）：

```
python tools/accessor_collisions.py src/main/java <GTLCore>/src/main/java
python tools/mixin_overlap.py      src/main/java <GTLCore>/src/main/java
python tools/method_overlap.py     src/main/java <GTLCore>/src/main/java
```

`accessor_collisions.py` 用未改名的旧源码运行时会报出上述 3 组共 8 个成员，用本工作树运行时报 `(none)`。

单元测试：

- `GtlCompatTest`：模式解析表、`standDown` 判定表、presence 缓存策略。
- `GtlStandDownSelectionTest`：gtlcore 存在/不存在 × 三种模式下的 CPU mixin 取舍；`CraftingCalculationMixin` 在 GTL 旁保持应用。
- `CraftingCalculationMixinContractTest`：源码契约——`@WrapMethod` 挂在 `computePlan`，不注入 `run`，GTL 让步用 park 而不是 AE2 monitor。
- `ThunderboltAccessorNamespaceTest`：扫描访问器源码，禁止 `getTasks` / `getValue` / `invokeAddMaxItems` 等 8 个名字再次出现。

交叉面复核（对照 GTLCore 1.2.3.2-fix1 源码，与整合包内 jar 同版本）：

- 两个模组共同修改的类共 **13** 个（`CraftConfirmMenu`、`CraftConfirmScreen`、`CPUSelectionList`、`Tooltips`、`CraftingPlanSummary`、`CraftingService`、`GridNode`、`Level`、`ExecutingCraftingJob` 及其 `TaskProgress`、`ElapsedTimeTracker`、`CraftingCalculation`、`CraftingCpuLogic`）。
- 其中 **1** 个类存在"GTLCore `@Overwrite` 落在 Thunderbolt 注入的方法上"：`CraftingCpuLogic`（`tickCraftingLogic` / `executeCrafting`）——正是 `GTL_OWNED_MIXINS` 让位的 CPU 家族。`CraftingCalculation` 上 GTLCore 覆盖 `run` / `finish` / `simulateFor`，Thunderbolt 改挂 `computePlan`，二者不再对打。其余类只有互不重叠的附加注入或访问器，`CraftingService` 上 8 个同名方法双方都只是 `@Inject`，不存在覆盖。
- 访问器生成的成员名（`tasks` / `waitingFor` / `timeTracker` / `link` / `value` 等）改名后已无重名，`tools/accessor_collisions.py` 输出 `(none)`。

带真实 jar 的运行期验证（Forge 47.1.3 开发环境，`run/mods` 放入整合包自带的 `gtlcore-1.2.3.2-fix1.jar`、`gtceu-1.20.1-1.4.4.jar`、`gtladditions`、`gtmthings`、`ae2wtlib`、`ad_astra`、`ExtendedAE`、`extendedae_plus`、`mae2`、`ae2ct`、`mererequester` 等 23 个 jar，AE2 由开发环境类路径提供）：

- 未加任何 `-D` 开关，日志出现 `GTL stand-down (mode AUTO)`，对 `CraftingCpuLogicBatchMixin` 以及依赖插件存在的 `ExtendedAePlusSuperMatrixBatchMixin` 各打印一条 INFO 并跳过应用——即真实 `gtlcore` 被识别，CPU 派发让位优先级高于"附加模组在场"规则。规划器 mixin 不再出现在跳过列表中。
- Thunderbolt 其余 mixin 照常选中（`CraftingCpuLogicAccessor -> appeng.crafting.execution.CraftingCpuLogic : true` 等），全程无任何 Thunderbolt 相关的 `InjectionError`。
- 同一 JVM 中 GTLCore 自身的 AE2 mixin（如 `PatternProviderLogicMixin`、`CraftConfirmMenuMixin`）正常准备工作。

上述 jar 验证发生在规划器仍随 CPU 一起让位的旧实现上。接入 `computePlan` 之后，需要再在整合包里确认：日志不再跳过 `CraftingCalculationMixin`，且一次普通配方与一次 GTL 专用配方都能算出计划。

### 实机验证的进展与阻塞

上面几轮运行**都没有跑到启动完成**，失败点都不在 Thunderbolt，而是开发环境与整合包的版本差：

- 整合包按 Forge 47.4.16 构建，开发环境是 47.1.3。`gtl_enhancedcore`、`Industrial Platform`、`alltheleaks`、`Shrink`、FTB 系列声明的最低 Forge 高于 47.1.3，必须先剔除。
- 更硬的阻塞是 GTCEu：`gtceu-1.20.1-1.4.4.jar` 的 `gtceu.refmap.json` 里缺少 `EntityMixin` 对 `getType` 的映射项（`data.searge` 中该 mixin 只有 `fireImmune` / `<init>` / `getMaxAirSupply`），任何开发环境加载这个已发布 jar 都会在 `@Shadow m_6095_` 处失败。GTCEu 是 GTLCore 的必需依赖，去掉它 GTLCore 就不会加载，因此**该整合包在本机开发环境里无法完整启动**。`kubejs`、`modernfix` 也是同类现象。
- 用户在 PrismLauncher 中的 GTL 实例当时正在运行，未对其做任何改动（未放入 jar、未重启），所以生产映射下的完整启动留待用户侧进行。

## 待实机验证（合并前必须完成）

在整合包（Forge 47.4.16）里放入本工作树构建的 jar，且 **AE2LT 与 Thunderbolt 同时在场**：

1. 启动到主菜单，确认日志出现 CPU 派发让位 INFO、**不**跳过 `CraftingCalculationMixin`，且无 `InjectionError`。
2. 进入世界并合成一次普通配方与一次 GTL 专用配方（样板展开 / 催化剂），确认 Thunderbolt 规划器能在 GTL 的 `run()` 里给出计划；引擎失败时应回退到 GTL / AE2 原版树，而不是卡死或抛 `PlanningCandidateDeclinedException`。
3. 确认 Thunderbolt 保留项可用：频道最大流、弹出、索引存储、扩展合成 CPU。扩展 CPU 能接下 `LoopCraftingPlan`；没有扩展 CPU 时剩余计划交给 GTL，而不是 `CPU_OFFLINE`。
4. 以 `-Dthunderbolt.gtlCompat=never` 启动，确认能复现 CPU 派发侧的失败（预期为启动注入失败或派发异常），以便日后判断故障来源。
5. AE2LT 同时在场时的连带验证见下节。

## AE2LT 侧的连带影响（不在本次范围）

AE2LT 自带 `mixin/thunderbolt/CraftingCpuLogicMixin` 与 `Ae2LtTimeWheelCraftingCpuLogic`，会与 GTLCore 的 `CraftingCpuLogicMixin`（`priority = 1100`）在同一目标上相遇。Thunderbolt 的让位只保证自身与 GTLCore 共存，不改变 AE2LT 的注入结果；AE2LT 需要按其自身策略单独适配与验证。

## 后续维护约定

- GTLCore 升级后重跑上面三个脚本；新增冲突时优先扩大 `GTL_OWNED_MIXINS` 让位范围，而不是调整注入优先级去争同一个被覆盖的方法。规划器继续挂在覆盖后仍被调用的方法上，不要改回注入 `run()`。
- 新增绑定 AE2 目标的访问器/注入成员一律使用 `thunderbolt$` 前缀（`ThunderboltAccessorNamespaceTest` 会为已知冲突成员守住这条线）。
