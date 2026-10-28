# GTO / NeoECO 实体机器测试（2026-10-21）

> 历史记录：2026-10-21 已按要求移除本分支全部 NeoECO 专用兼容。本文中的 NeoECO 实现、用法和测试结果对应此前构建，不代表当前支持范围；当前说明见 [GTO_COMPATIBILITY.md](GTO_COMPATIBILITY.md)。

> 本文保留修复前失败证据。后续已修复计数器类型与 UUID 适配，并纠正测试夹具的供电连接和 AppliedE 回调调度；最终同套实机 16/16 通过，详见 [修复报告](GTO_ECO_FIX_20261021.zh-CN.md)。

**未通过验收：最终 16 项中 12 项通过、4 项失败，启动失败数 0。** 本轮新增 4 项真实 ECO 机器测试，其中 1 项通过、3 项被同一个集合类型兼容性错误阻断；原有 12 项仍为 11 项通过、AppliedE 实体 CPU 用例超时。

## 测试对象与环境

- Thunderbolt：`build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`，SHA-256 `2855b041a46d85e9b6c99dca2f26ab084e432522f0c58479c14e71ac122028ce`。
- Java 21.0.10、Minecraft 1.20.1、Forge 47.4.20、GTOCore 26.10.5、其原包内嵌 AE2-GTO 15.2610.8、AE2LT 2.1.2-gto.1。
- NeoECO：已有离线迁移副本 `neoecoae-20.3.0-gto.jar`，SHA-256 `9f21fe3beab0ddb687bf72ebb217b61f6851ed869b902d78bd23ef943c4b72c5`。
- 独立专用服务器、端口 25606、全新世界 `compat-world-batch-physical4`。本轮没有修改生产源码、正式 JAR 或相邻模组项目。

这次运行真实 Forge 服务端和经过 Mixin 转换的模组类，不使用模拟 ECO 供应器。测试模组搭建完整 5×3×2 ECO 结构，含控制器、真实工作器、并行核心、样板总线、接口、输入输出仓、散热与 AE 创造能源。每项新增测试先通过 NeoECO 原生内部结构校验，主动计算多方块，再检查有效工作容量和工作器的 AE 网络连接。

## 新增实体测试结果

| 用例 | 实际执行 | 结果 |
| --- | --- | --- |
| `nativeBusUuidControl` | 绕过 TB，直接调用真实 ECO 总线的三参数 UUID 派发；计划读取工作器 NBT 中的任务 UUID | 失败：复制非空输入时 `ClassCastException`，未到 UUID 断言 |
| `thunderboltBusDispatchMustPreserveJobUuid` | 调用发布包的 `NeoEcoFastPathCompat.pushPattern()`，再检查真实工作器 NBT | 失败：相同异常，未到 UUID 断言 |
| `ordinaryCraftActuallyDeliversFourPlanks` | 普通派发一份配方，计划等待真实网络收到 4 个木板、无原木退款且工作线程结束 | 失败：派发时相同异常，未验收到实际产物 |
| `unsupportedExactAndCancelLeavePhysicalWorkerUnchanged` | 在真实总线上请求 `2^126` 精确批次并通知取消，比较前后工作器 NBT 和输入数量 | 通过：缺少可选接口时返回 null / 无操作，输入和工作器不变 |

前三个失败代表一个已复现的兼容性故障，不能计为三个独立根因，也不能将产物交付或 UUID 保留写成实测通过。

## 已定位的集合类型兼容性故障

实际运行栈为：

```text
ClassCastException:
  Reference2LongOpenHashMap$MapEntry cannot be cast to Object2LongMap$Entry
ECOFastPathStacks.copySorted(ECOFastPathStacks.java:178)
ECOFastPathStacks.copyCounters(ECOFastPathStacks.java:52)
ECOExtractedPatternExecution.slow(ECOExtractedPatternExecution.java:74)
ECOCraftingPatternBusBlockEntity.pushPattern(ECOCraftingPatternBusBlockEntity.java:81)
```

NeoECO 对 `KeyCounter` 进行 `Object2LongMap.Entry<AEKey>` 的增强 for 遍历。实际 AE2-GTO 的非空计数器返回 `Reference2LongMap.Entry`，两者不兼容；已安装迁移副本的字节码仍保留失败的 `CHECKCAST Object2LongMap$Entry`。官方 20.3.0 源码与当前 JAR 的反编译结果互相印证。

原生三参数调用同样失败，说明故障发生在 ECO 与 AE2-GTO 的输入复制边界，不能归因于本轮 TB 的反射入口，也不能仅通过关闭批量适配器解决。现有附属迁移工具的“可加载”结论不足以证明普通 ECO 工作器可运行。此问题本轮仅定位并保留证据，未修改 NeoECO 制品来掩盖失败。

## UUID 与新版接口的验证边界

实际 20.3.0 总线已经提供原生 `pushPattern(IPatternDetails, KeyCounter[], UUID)`；其二参数版本向三参数版本传入 null。当前 TB 入口仅探测 `ECOJobAwareCraftingProvider` / `ECOFastPathFacade`，这些类型在当前 20.3.0 制品不存在，因此本轮堆栈确认走入二参数普通回退。这是一个需要后续补齐的原生 UUID 适配缺口。

**尚未在实际工作器 NBT 中观察到 UUID 丢失**：本轮先被输入复制异常阻断。原生 UUID 对照组也未到达 NBT 检查，不能把静态调用链推断写成 UUID 动态验收结果。本轮也没有证明默认 GTO CPU 会经过该公共兼容入口的所有分支。

本地检查的 Forge 1.20.1 NeoECO 20.3.0、20.4.1、20.4.2 制品均没有此次移植使用的 facade、job-aware 和 exact provider 类型。网络版本列表中更高的 21.x 为 NeoForge / Minecraft 1.21.1，不能直接代入当前环境。因此新版精确交接、取消后回收等能力仍只有此前隔离协议测试证据，没有真实兼容 Forge 制品的交接验收。本次 no-op 测试通过只说明缺少接口时没有副作用。

## 原有用例与服务器退出

原有注册生命周期、NBT 复制、批量拒收退款、异常不重放、具体接收量约束等 11 项通过。`actualAppliedEModuleConsumesEmcAndFinishesOnPhysicalGtoCpu` 仍在 150 tick 超时；本轮未将它归因于 ECO 集合错误，也未延长超时或删掉用例。

CPU、STORAGE、PLANNER 三项启动探针通过，它们不额外计入 16 项 GameTest。最终日志为：

```text
GTO_COMPAT_QA FAIL total=16 failed=4 startupFailures=0
```

服务端完成全部维度保存并正常退出，JVM 退出码 0；启动器因测试失败返回 1。服务器正常退出不代表功能测试通过。

## 配方夹具与失败轮次

GTO 删除了原版木板配方，也会忽略运行时 `RecipeManager.replaceRecipes()` 替换。前三轮的新增用例停在配方查找，结果均为 11/16 通过；日志已保留，不能算作机器功能验证。

第四轮仅向隔离服务器的配方管理器加入 `thunderbolt_eco_runtime:oak_planks`：1 个橡木原木 → 4 个橡木板，同时补齐按名称及类型查询表，保留其他配方。使用真实 AE 编码/解码的合成样板和普通 `KeyCounter`。夹具没有替换工作器、总线、AE 计数器或实际提交实现，配方修正后暴露了上述生产依赖兼容性异常。

## 复现与证据

所有运行文件保存在 `D:/codex-builds/thunderbolt-eco-runtime-20261021/`，避免占用空间不足的工作区所在盘：

- `probe-src/EcoRuntimeTests.java`、`probe-src/FullQaProbe.java`、`build_probe.py`：测试源码和构建入口。
- `probe-physical4.jar`、`fixtures/full-qa-probe.jar`：最终测试模组；原有模组仅增加测试注册与 4 个实体用例。
- `server-batch-physical4.log`、`debug-physical4.log`、`runner-physical4.log`、`runtime-results-physical4.json`：完整最终运行和异常栈。
- `runtime-results-physical1.json` 至 `physical3.json` 及对应日志：早期夹具失败记录。
- `ECOFastPathStacks.java`、`ECOFastPathStacks.javap.txt`、`GtoKeyCounter.javap.txt`：源码与实际字节码证据。
- `tested-current-release.jar`、`artifact-manifest.json`、`mod-hashes.json`、`probe-changes.json`、`runtime-summary.json`：冻结制品与差异清单。

在原测试环境使用新后缀和全新世界复现：

```powershell
$env:THUNDERBOLT_QA_JAR='D:/codex-builds/thunderbolt-eco-runtime-20261021/tested-current-release.jar'
$env:THUNDERBOLT_QA_LABEL='-physical-next'
$env:THUNDERBOLT_QA_NBT='true'
$env:THUNDERBOLT_QA_RESTART='false'
python D:/codex-builds/thunderbolt-eco-runtime-20261021/run_server.py batch
```

本轮为专用服务器上的实体机器测试，没有新增客户端界面、完整 CPU 下单到交付、跨进程重启恢复或全部整合包配方的验收结论。此前隔离协议测试记录见 [ECO 移植报告](GTO_ECO_PORT_20261021.zh-CN.md)。
