# Thunderbolt 内置 NeoECO 加载期兼容（2026-10-21）

> 历史记录：2026-10-21 已按要求移除本分支全部 NeoECO 专用兼容。本文中的 NeoECO 实现、用法和测试结果对应此前构建，不代表当前支持范围；当前说明见 [GTO_COMPATIBILITY.md](GTO_COMPATIBILITY.md)。

Thunderbolt 现在内置 NeoECO 的 GTO 适配。对于本次验证的 **NeoECO 20.3.0 原版 JAR**，无需再运行离线迁移工具或替换第三方修复包；安装新 TB 与原版 NeoECO 即可。

## 实现

在 `META-INF/coremods.json` 注册 `coremods/gto_neoeco_compat.js`，由 Forge 在请求对应类时转换内存中的字节码，不写入第三方 JAR，不替换 AE2/GTO 类。目标仅为已审计的 12 个 NeoECO 类，延续 [此前修复](GTO_ECO_FIX_20261021.zh-CN.md) 的行为：

- 将 8 个类中已审计的 `KeyCounter` 遍历条目改为 `Reference2LongMap.Entry`，同步处理方法所有者、栈帧、局部变量、签名和内部类元数据。
- 适配 NeoECO 的 `CraftingServiceMixin.currentlyCrafting` 字段及访问描述符为 `AEKeySet`。
- 适配计划的 `patternTimes()` 返回类型和构造器参数；旧 Map 参数复制为 `Object2LongOpenHashMap`，保留 long 数量。
- 为样板总线的有效样板库存补齐 `IItemHandlerModifiable`，设置操作委托已有 `setItemDirect`。

前一轮已加入 TB 的原生 UUID 总线派发继续保留。新脚本不提前加载 NeoECO 的 Java 类；已经使用旧迁移副本的类也不会重复添加方法或接口。

转换针对审计过的类名和旧调用形态。当前已验证版本为 20.3.0，不据此承诺所有后续 NeoECO 版本、所有可选集成或其他附属都兼容。

## 验证

新增回归测试逐一读取官方原包的 12 个类：加载期转换结果与已验证离线工具的输出在规范化后逐字节相同；再次转换以及传入已修补类均保持不变。相关测试共 **103 项：91 通过、12 跳过、0 失败**。12 项跳过仍是缺少真实新版协议制品的原有用例，不能视为新版精确交接验收。`compileGameTestJava`、`verifyReleaseJar` 和差异空白检查通过。

真实环境保持 Java 21.0.10、Minecraft 1.20.1、Forge 47.4.20、GTOCore 26.10.5、其原包内嵌 AE2-GTO 15.2610.8、AE2LT 2.1.2-gto.1。其他附属沿用此前冻结测试环境，不将它们计为本轮原包自动兼容。

原版 NeoECO 专用服务器使用端口 25608、全新世界 `compat-world-batch-original1`，以及上一轮通过的相同实体测试夹具，**16/16 通过，启动失败 0**：

- 原生 ECO 多方块校验、有效容量与接口供电通过。
- 原生 UUID 对照组及 TB 派发均在真实工作器 NBT 中保留正确 UUID。
- 真实工作器完成 1 个橡木原木 → 4 个橡木板，网络收到产物、无原木退款、线程结束。
- 缺少新版 exact / cancel API 时不修改工作器或输入。
- 既有 AppliedE CPU 测试交付 65 个钻石、扣除 EMC 532480；该测试仍使用原有账户/节点代理和已迁移的 AppliedE，不代表 AppliedE 原包已由 TB 自动兼容。
- 其他批量记账、NBT 复制与注册生命周期用例通过。

服务器完成所有维度保存，JVM 和启动器返回 0。日志显示的原版 NeoECO 文件沿用测试实例的旧文件名，但内容 SHA-256 与官方原始输入完全一致，且没有迁移标记资源。

未安装 NeoECO 的隔离实例使用相同 TB：4 个 ECO 实体用例不注册，原有可选适配器用例明确记录 `SKIP (NeoECO absent)`，其余 **11/11 通过，启动失败 0**。服务端保存所有维度并正常退出，JVM 和启动器返回 0。结果见 `runtime-results-no-eco.json`；不将缺失模组的 ECO 用例计为通过。

本轮没有重跑客户端 GUI、完整玩家下单、跨 JVM 恢复或全部模组配方。CPU 菜单等未在专用服务器执行的类只有转换等价性测试证据。

## 使用与制品

替换 `build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`。NeoECO 使用原版 `neoecoae-20.3.0.jar`，同一实例只保留一个 NeoECO JAR，客户端与服务端使用相同组合。

| 制品 | SHA-256 |
| --- | --- |
| 新 TB 发布包 | `c997697bea198738dcf6c96609d1e184e464117d97989330d24c9ca83eec6672` |
| 官方 NeoECO 20.3.0 原包 | `b832af4000f33af4bf62fadf26df7a09b9c2a839d5fc5664ee9b6c140e61367f` |

与前一轮修复包相比，TB 仅新增 `coremods/gto_neoeco_compat.js` 并修改 `META-INF/coremods.json`，其他条目不变，不包含测试代码或第三方类。旧 NeoECO 离线迁移工具保留为备用方式；AdvancedAE、AppliedE、Crafting Tree 等附属仍沿用各自已有迁移边界。

## 证据与复现

证据目录：`D:/codex-builds/thunderbolt-neoeco-runtime-compat-20261021/`。

- `build-test.log`、`shape-test.log`、`final-regression.log`、`regression/xml/`：转换等价性与相关回归。
- `tested-runtime-compat.jar`、`unit-summary.json`、`jar-entry-differences.json`、`results.json`：冻结发布包和汇总。
- `runtime/tested-original-neoecoae-20.3.0.jar`、`runtime/probe-original1.jar`、`runtime/server-batch-original1.log`、`runtime/debug-original1.log`、`runtime/runtime-results-original1.json`：原包实体机器测试。
- `runtime/probe-src/`、`runtime/appliede-probe-src/`、`runtime/build_probe.py`、`runtime/run_server.py`：测试夹具与运行入口；`runtime/runner-no-eco.log` 及对应日志/JSON 保存可选模组缺失检查。

构建：

```powershell
.\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false -I D:/codex-builds/thunderbolt-neoeco-runtime-compat-20261021/compat.init.gradle neoEcoRuntimeRegression compileGameTestJava verifyReleaseJar
```

实际服务端测试在隔离目录执行，未覆盖此前的失败或修复记录，也未修改相邻模组项目。
