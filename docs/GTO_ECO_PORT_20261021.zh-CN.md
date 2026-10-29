# GTO ECO 任务归属与精确交接移植（2026-10-21）

> 历史记录：2026-10-21 已按要求移除本分支全部 NeoECO 专用兼容。本文中的 NeoECO 实现、用法和测试结果对应此前构建，不代表当前支持范围；当前说明见 [GTO_COMPATIBILITY.md](GTO_COMPATIBILITY.md)。

> 后续实体测试未通过：真实 NeoECO 20.3.0 工作器在普通派发时出现 `Reference2LongMap.Entry` / `Object2LongMap.Entry` 类型转换异常；最终 16 项中 12 项通过、4 项失败。下面的隔离协议测试和历史 11/12 结果保持原记录，不能据此宣称实体 ECO 交付通过。详见 [实体机器实测报告](GTO_ECO_RUNTIME_20261021.zh-CN.md)。

> 再后续修复已完成：使用新版 TB、重新生成的 NeoECO 迁移副本与修正的测试夹具，实体套件 16/16 通过；原生工作器 UUID 和实际产物交付已验证。新版 exact facade 在真实 Forge 制品上仍不可用，验证范围见 [修复报告](GTO_ECO_FIX_20261021.zh-CN.md)。

已移植上游 GTL [`8ad2d57ce7cad5b02857b9e4fc8f37533764ff16`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/8ad2d57ce7cad5b02857b9e4fc8f37533764ff16)。它是主线 `9cb676b` 在 Forge 1.20.1 的适配版本。本轮保留当前 Java 21、GTO 原生调用和 NeoECO 20.3.0 依赖锁定，以及此前已测试的通道换算率修复。

## 行为与适配

- `NeoEcoFastPathCompat.pushPattern()` 探测可选的任务归属接口；支持时传递订单 UUID，不支持时继续普通派发。异常和 Error 传播给调用方，不进行第二次提交。
- allocated 批次不支持或明确拒收后的单份回退使用上述入口，继续复制借用输入并保留接受状态不确定时的不退款保护。
- Forge 协议桥在准备普通回退时捕获当前 UUID，避免延迟提交读取已经改变的实时 `BatchJobView`。
- 提供可选 `prepareExact()` / `ExactBatch`，保持 BigInteger 数量、检查接收数量为正且不超过请求数量，并分开准备和提交。
- 提供可选 `cancelJob()` 通知。接口不存在时返回，通知调用失败记录日志。

三个生产文件均按当前工作区适配，未整文件覆盖已有 GTO 逻辑。精确交接和取消通知是供独立 CPU 使用的接口；本次没有自动替换其他模组的 CPU 生命周期、材料提取、能量或产物结算逻辑。

## 回归验证

先只加入上游隔离协议测试及六个测试资源，再对移植前生产代码执行：14 项中 13 项失败，原有的接受状态不确定保护仍通过。原始日志和 XML 保留。

移植后 ECO、批量执行、调度与 CPU 记账相关测试共 **95 项：83 通过、12 跳过、0 失败**。新增的 14 项隔离协议测试全部通过，覆盖：

- 订单标识、准备时捕获 UUID、后续单份调用和输入副本所有权。
- 明确拒收、接受状态不确定、普通提交异常及 Error 不重试。
- 超过 long 的精确数量、非法接收数量、准备/提交分离和失败传播。
- 取消通知、通知失败隔离、旧 facade 缺少新方法时的回退。

另移入一项无关供应器直接调用测试，通过。12 项跳过来自需要外部真实 NeoECO 新协议制品的原有测试，本次未提供该制品；不将跳过项计为通过。`compileGameTestJava`、`verifyReleaseJar` 和 `git diff --check` 通过。本轮未重跑完整规划器套件。

构建复用 D 盘现有编译目录，但测试使用独立任务与报告目录，不覆盖此前基线 XML。命令为：

```powershell
.\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false -I D:/codex-builds/thunderbolt-eco-port-20261021/port.init.gradle ecoPortRegression compileGameTestJava verifyReleaseJar
```

## 实际 GTO 服务器

使用本轮冻结发布包、未修改的原有测试夹具和全新世界 `compat-world-batch-eco-port`。环境保持 Java 21.0.10、Minecraft 1.20.1、Forge 47.4.20、GTOCore 26.10.5、AE2-GTO 15.2610.8、AE2LT 2.1.2-gto.1，以及既有迁移副本 NeoECO 20.3.0。

批量套件 **11/12 通过，启动失败数 0**。异常不重放、拒收退款、具体接收量约束等用例通过。名称为 `optionalAdaptersRegisterAndNeoEcoAcceptsPartialBatch` 的用例在 20.3.0 上实际执行的是旧版能力边界检查：确认没有注册不支持的批量适配器，保留普通派发路径；不能将其写成新版部分接收协议已经实机通过。

唯一失败为此前已记录的 AppliedE 实体 CPU 用例，在 150 tick 超时。服务器完成保存并正常退出，JVM 退出码 0；启动器因该用例失败返回 1，整套结果仍为未全通过。

**新 ECO 接口已通过隔离协议测试，尚未与提供这些接口的真实 Forge ECO 制品完成游戏内交接验收。** 当前锁定的 NeoECO 20.3.0 不会因本次移植自动获得新版能力。本轮没有升级相邻 AE2LT 项目或修改其源码。

## 发布包与证据

交付路径：`build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`，与实际服务器使用的冻结副本逐字节一致。

SHA-256：`2855b041a46d85e9b6c99dca2f26ab084e432522f0c58479c14e71ac122028ce`。

发布包 613 个条目，不含 NeoECO 协议夹具、测试资源、测试代理或 GameTest 类。与此前通道换算率修复包比较，新增 3 个条目、修改 5 个条目，全部位于 NeoECO 兼容包；没有删除条目，其余内容一致。

证据位于 `D:/codex-builds/thunderbolt-eco-port-20261021/`，包括移植前后源码、精确差异、两阶段测试 XML、构建日志、逐 JAR 条目差异、冻结发布包和实际服务器结果。原有通道换算率测试包及历史失败记录继续保留。原始世界和服务器日志仍在既有隔离实例目录，本轮日志与结果另复制到新证据目录。
