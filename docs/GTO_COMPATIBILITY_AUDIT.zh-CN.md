# GTO 兼容性检查（2026-10-19）

> 历史记录：2026-10-21 已按要求移除本分支全部 NeoECO 专用兼容。本文中的 NeoECO 实现、用法和测试结果对应此前构建，不代表当前支持范围；当前说明见 [GTO_COMPATIBILITY.md](GTO_COMPATIBILITY.md)。

本次检查确认：TB 与配套 GTO 的核心调用可以解析。现已提供 AdvancedAE、NeoECO、AE2 Crafting Tree 三个已检查版本的离线迁移工具，CPU 基础测试中的重复 Tick 问题也已修复。AppliedE 新增了限定 Thunderbolt V2 规划的迁移方案并通过真实交付测试；GTO 原生规划仍不支持其样板。ExtendedAE Plus 仍未完成迁移，不能宣称全部兼容。

迁移工具源码位于 `src/compatTool`，构建任务为 `gtoAddonPatcherJar`。支持的输入哈希、具体修改和使用方法见 [GTO_ADDON_MIGRATION.zh-CN.md](GTO_ADDON_MIGRATION.zh-CN.md)。以下失配表描述原始普通版 JAR，生成的 GTO 副本需要单独验证。

## 确认不兼容的普通版附属

这些原始 JAR 来自本项目的可选模组测试依赖。首次扫描时未安装到 GTO 服务端实例；后续将三个迁移副本安装到隔离实例进行复验。检查的是实际 JAR 的方法/字段引用，并用 AE2-GTO 15.2610.8 的类及继承关系解析；下表描述原始包，结论不依赖 Minecraft 方法名称的混淆映射。

| 检查的 JAR | 失配接口 | 触发路径 |
| --- | --- | --- |
| `advancedae-1.3.6-1.20.1.jar` | `ICraftingPlan.patternTimes(): Map`；末参数为 `Map` 的 `CraftingPlan` 构造器 | AdvancedAE 的 `ExecutingCraftingJob` 和 `AdvCraftingCPUCluster` |
| `neoecoae-20.3.0.jar` | 同上两项 | NeoECO 的 `ExecutingCraftingJob` 和 `ECOCraftingCPU` |
| `ae2-crafting-tree-1086241-7182165.jar` | `CraftingPlan.patternTimes(): Map` | `RecipeHelper` 创建合成树 |
| `appliede-0.14.3.jar` | `ICraftingPlan.patternTimes(): Map`；旧的 `CraftingTreeProcess` 构造器 | 确认菜单及合成树 Mixin |
| `extendedae-plus-pN9pMjiW.jar` | `ICraftingPlan.patternTimes(): Map` | `ForcedCraftingPlan` |

AE2-GTO 对应的方法返回值和构造器参数已经改为 `it.unimi.dsi.fastutil.objects.Object2LongMap`。Java 能把它当作 `Map` 使用，但 JVM 方法描述符要求完全匹配，普通版 JAR 的旧调用会在执行到相关路径时产生 `NoSuchMethodError`；Mixin 的目标方法变化也可能更早阻止加载。需要使用经过 GTO 适配的附属版本，或在附属源码中完成移植后重新编译，不能靠关闭 TB 批量适配器解决。

现有 `OptionalIntegrationBinaryShapeTest` 验证 TB 的注入目标在附属 JAR 中存在；它不等于附属本身调用 AE2-GTO 的所有接口都兼容。其他客户端方法的未解析项未用作本报告的确认依据，因为它们还涉及混淆映射。

## 本项目已修复

- 提供独立离线迁移工具，为已核对哈希的 AdvancedAE 1.3.6、NeoECO 20.3.0、AE2 Crafting Tree 1.1.1 生成 GTO 副本。修复计划 API、`AEKeySet` 影子字段、AdvancedAE 局部变量捕获，以及 NeoECO 可修改库存接口。资源、refmap、嵌套依赖和原始 JAR 保留。
- 安装附属后的真实启动复现了 GTCEu `ChemicalHelper.getMaterialEntry` 懒初始化材料缓存的并发扩容越界。TB 现在通过 Forge 的 METHOD 核心转换器将这个具体签名的静态方法标记为同步，覆盖读取、批量填充、清空待处理项及后续读取；没有替换 GTO/GTCEu 实现。新增测试执行实际的 JavaScript 转换器，验证目标签名、并发互斥及异常后释放锁。实机另有独立夹具通过反射断言该方法确实带 `synchronized`，记录见 `cache-runtime-validation.txt`。
- LT 的 `BigCraftingPlan.summary()` 直接创建摘要，绕过 Crafting Tree 的原生工厂挂钩，导致确认包序列化时 `jobs == null`。TB 现在在 LT 返回摘要后补全 Crafting Tree 显示载荷，保留原摘要的精确数量报告及可执行状态；仅 LT 存在时启用。
- `LoopCraftingPlan` 包装原始计划后，精确数量报告仍附着在原对象上。现在读取报告会查找其委托，保留精确显示及仅预览提交限制。新增测试先复现失败，再验证修复。
- 旧 LT 使用三参数 `ExactPlanReport` 构造器创建可执行订单的摘要；新版报告默认带仅预览标记。现在可执行摘要的界面状态遵循摘要自身的 `simulation` 标志，实际 `ICraftingPlan` 的仅预览提交检查仍保留。

## 本次验证

环境：Minecraft 1.20.1、Java 21.0.10、Forge 47.4.20、GTOCore 26.10.5、其内嵌 AE2-GTO 15.2610.8，以及配套 LT 2.1.2-gto.1。服务端位于独立测试目录，使用新建存档；LT 测试包的生产类与原包一致，额外加入测试夹具。最后的大数复验锁定了夹具副本，避免其他构建更新共用 JAR。

| 检查 | 结果 |
| --- | --- |
| TB 相关二进制方法/字段引用 | 6,556 处，未解析 0 |
| 配套 LT（含锁定测试夹具）对 TB 的二进制引用 | 392 处，未解析 0 |
| 前次兼容性回归测试 | 38 通过，12 项 NeoECO 协议测试因缺少对应运行依赖跳过，失败 0 |
| 最终全量 `check` | 1,475 通过、13 跳过、失败 0（共 1,488 项） |
| 最终兼容性回归 | 12/12 通过：真实 GTO 构造/读取、发布附属迁移、库存 setter、实际核心转换器互斥及异常释放、Crafting Tree 载荷桥接及可选 Mixin 选择 |
| 三个迁移附属对 AE2 的引用扫描 | 2,677 处；确认的非混淆 API 失配 0；5 处客户端继承方法映射尚待客户端复验 |
| `jarJar` / `verifyReleaseJar` | 通过 |
| GTO 实际启动检查（最终附属组合） | CPU 桥接、库存去重/精确数量/挂载顺序、TB 规划及原生回退通过；缓存同步锁反射断言通过 |
| 前次大数订单实机测试（未加三个附属） | 13 通过，启动检查失败 0；NeoECO 实物用例因未安装而跳过 |
| 前次批量派发实机测试（未加三个附属） | 4 通过，启动检查失败 0；NeoECO 实物用例因未安装而跳过 |
| 基础实机测试（CPU 测试修复后，未加三个附属） | 11/11 通过，启动检查失败 0 |
| 基础实机测试（最终发布包 + 三个迁移附属） | 11/11 通过，启动检查失败 0 |
| 大数订单实机测试（最终发布包 + 三个迁移附属） | 14/14 通过，含确认包往返保留精确数量与可执行状态、旧版 NeoECO 普通派发回退断言；启动检查失败 0 |
| 批量派发实机测试（最终发布包 + 三个迁移附属） | 5/5 通过，含旧版 NeoECO 适配器注册边界断言；启动检查失败 0 |

最终全量 `check` 已通过：1,488 项中 **1,475 通过、13 跳过、失败 0**。见 `final-full-check.log`、`final-full-unit-results.json`。前期高负载下曾出现 5 项失败，单独复验还有 3 项涉及 CP-SAT 返回 `UNKNOWN`、局部优化预算及 3 秒大图超时；这些历史失败记录保留。最后本轮三个用例全部通过，未修改断言或增大测试超时。最后的测试进程使用 `AboveNormal` 调度优先级，减少与本机其他项目的竞争；结果不代表所有并行负载下都能满足相同墙钟预算。

基础测试原先未通过的是 `physicalGtoCpuPreservesOverloadPauseSaveReturnAndNativeFallback`：期望下一次手动 Tick 后 `pushes == returns + 1`，实测 `pushes=3, returns=1`。现已确认测试同时启用了自动调度及手动 Tick。修复位于配套 LT 的 `src/gtoQa/java/qa/GtoCpuGameTests.java`：两个延迟回调之间保留暂停状态，每次只在被测的手动 Tick 周围解除暂停，结束时恢复执行。原有单次操作预算、剩余产物、存档恢复、原生回退断言全部保留。

修复后的基础实机复验见 `server-base-cpu-fixed.log`：**11/11 通过，启动检查失败 0**。这次使用已锁定的原完整 GTO 依赖，尚未安装三个迁移后的附属；因此该结果证明 CPU 测试修复有效，不等于附属组合已经验证。

NeoECO 20.3.0 不提供较新版本的 `ECOBatchCapacityProvider`、批量回执或 FastPath API。最终夹具对这个版本验证未注册不支持的批量适配器，并保留普通派发；不将旧版回退测试写成原生批量回执已通过。LT 测试源码的两项能力判断已修正，锁定的测试包仅更新对应测试类，生产类未变。

原始 AppliedE 依赖已删除的 `CraftingTreeNode`、`CraftingCalculation`、`CraftingSimulationState`、`ExecutingCraftingJob`。本轮通过改用永久样板生命周期迁移这些路径，详见下节。ExtendedAE Plus 仍依赖这些旧规划/执行实现及 `GenericEntryStackHelper`、`EncodePatternTransferHandler`，当前工具继续拒绝它。核对上游 `1.20.1-gto`（1.6.2-GTO-1.3）发现其计划包装器仍返回 `Map`，条件插件还停用若干冲突功能，因此没有直接替换原 1.5.5 包并宣称修复。

本轮没有重新运行客户端渲染测试；三个迁移副本已同时安装复验，表中普通版原始 JAR 未作为可用组合验证。AE2-GTO 15.2610.10 与原版 GTOCore 26.10.5 的 `CreativeCellInventoryMixin` 字段类型冲突仍不受支持，不能单独升级 AE2。

## AppliedE 后续修复与实测

迁移版保留 `KnowledgeService` 原有单份转化和 EMC 等级换算样板，移除旧版请求尺寸临时样板的创建/清理 Mixin，避免链接已删除的合成树。`TransmutationPattern` 新增 GTO 公共 CPU 输入暂存接口，终端同步注入改按类型捕获局部变量，同时修正其依赖元数据。修改 2 类、移除 3 个过期 Mixin，EMC 价格、账户核算、知识库和模块队列保持原逻辑。

**仅支持通过 Thunderbolt V2 规划这些转化订单。** 原生 GTO 规划实测抛出 `Cannot build an operator ... pattern type may be unsupported`，只补 `IDetails` 接口不能解决。V2 的候选算法顺序必须在原生算法之前；原生算法优先的网络仍不能计算 AppliedE 订单。本轮未验证该附属的任务存档重启及极端跨等级数量。

| 本轮检查 | 结果 |
| --- | --- |
| 工具针对性回归 | 5/5 通过；包含迁移幂等、依赖元数据、类型捕获以及其他功能字节保留断言 |
| AppliedE 原生 GTO 规划 | 确认不支持自定义转化样板；失败证据保留 |
| AppliedE V2 规划及核算 | 65 份永久样板订单；EMC 模拟不扣费、实际扣费、退款、真实模块批量队列均通过，无临时样板残留 |
| 实体 GTO CPU 与真实模块交付 | 65 份钻石，扣除 532,480 EMC，模块经网络库存交付，最终库存 65，任务结束 |
| 最终服务端基础套件 | 12/12 通过（原 11 项加 AppliedE 实体 CPU 用例），启动失败 0，正常退出 |
| 最终客户端 | 进世界、渲染、精确数量和终端类变换通过，退出码 0；非全功能界面测试 |

实体 CPU 用例使用真实供电 CPU、AppliedE 模块类与 EMCStorage，账户和模块节点用测试代理提供；没有连接真实玩家 EMC 账户。前两次交付夹具错误地把无请求者任务的 `insert` 返回值当作接收量，修正后改走模块 `tickingRequest` 和实际网络交付，未放宽最终库存、扣费或任务完成断言。早期终端 LVT 崩溃与原生规划失败、测试夹具失败记录均保留。最终客户端退出时仍记录 ModernUI 死线程警告和 EMI 搜索后台线程的窗口清理异常，均发生于测试通过、开始退出之后；旧基线/联机日志中也有同类记录，没有将这些第三方退出问题标记为已修复。本轮仅修改独立工具，没有重建主模组；此前全量 `check` 结果仍对应前一轮主代码验证。

新增证据位于同一目录：`appliede-final-tests.log`、`appliede-v2-planner-pass.txt`、`appliede-native-pattern-failure.txt`、`appliede-runtime-validation.txt`、`appliede-cpu-validation.txt`、`server-base-appliede-delivery.log`、`appliede-delivery-runtime.log`、`client-appliede-final-console.log`。专属探针和实体 CPU 测试源码保存在 `fixtures/AppliedERuntimeProbe.java`、`fixtures/AppliedECpuGameTests.java`，编译/重混淆记录在 `appliede-probe/`。基础、exact、batch 的早期 AppliedE 候选运行分别通过 11、14、5 项，但不能代替后续完整交付断言。最终产物和摘要见 `appliede-repair-results.json`；`appliede-final-linkage-audit.json` 中 AppliedE 的 411 处 AE2 方法/字段引用未解析 0。

## 证据与构建

检测脚本、模组哈希、二进制报告及完整日志位于 `D:/codex-builds/thunderbolt-gto-compat-20261019`：

- `tb-linkage-audit.json`：核心引用及各普通版附属的失配调用者。
- `compat-loop-repro.log`、`compat-final-tests.log`、`unit-results.json`：修复前复现及修复后测试。
- `server-base-baseline.log`、`server-base-final.log`：旧版与修复版的基础测试对照。
- `server-exact-locked.log`：固定夹具后大数订单 13 项通过。
- `server-batch-final.log`：批量派发 4 项通过。
- `mod-hashes.json`、`fixtures/`、`run_compat.py`：测试实例和夹具快照。
- `patched-addons-linkage-audit.json`：最终三个迁移副本和当前 TB/LT 的引用扫描。
- `coremod-release.log`、`tree-bridge-release.log`：核心转换器、附属及摘要桥接修复的重混淆和发布包验证通过。
- `final-compat-tests.log`、`final-compat-unit-results.json`、`final-compat-test-results/`：最终 12 项回归通过。
- `server-exact-addons-release.log`：摘要空载荷崩溃及旧版 NeoECO 测试能力判断错误的复现。
- `legacy-neo-fixtures/`：两个测试类族的编译/重混淆记录，锁定生产类保持原始字节。
- `server-base-addons-verified.log`、`server-exact-addons-verified.log`、`server-batch-addons-verified.log`、`runtime-results-addons-verified.json`：最终发布包和三个迁移附属的 30 项实机测试全部通过，三组均正常退出。
- `cache-runtime-validation.txt`、`fixtures/cache-probe.jar`、`fixtures/GtoCacheProbe.java`：每次启动在实际定义的类上断言同步锁存在。
- `cache-plugin-trace.txt`、`server-base-addons-diagnose.log`：撤除旧标记 Mixin 前的回调缺失及并发越界复现；旧 Mixin 方式不属于最终发布构建。
- `planner-isolated-recheck.log`：前期三项性能测试单独复验的失败记录；最终全量复验中三项均已通过。
- `final-full-check.log`、`final-full-unit-results.json`、`final-full-test-results/`：最终全量检查通过及全部 XML 结果。
- `final-repair-results.json`：最终构建哈希、单元测试、实机矩阵及未支持范围。
- `addon-fix-check-final.log`、`full-check-loaded-results/`、`addon-final-recheck.log`：全量失败及针对 5 项失败的单独复验，保留未通过记录。
- `deliverables/`、`patched-addons-final/`、`repair-artifacts.json`：最终构建、附属迁移副本及 SHA-256。

更新后的主 JAR 为 `build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`，SHA-256：

```text
f8363ae5f35c0b5a62907544a41e28650be30f87e9929a01525e98e318612311
```

客户端与服务端须使用同一构建。

AppliedE 迁移副本 SHA-256：`1e3d3592f10255bd0d3534ddb62f9c902cc4ca043067af1a5ed85dac30e1ed6a`。本轮迁移工具 SHA-256：`aa6ff27cb1e87072c9c9e712fe7207a8529d55488171b7125a8d33cbe95132f2`。
