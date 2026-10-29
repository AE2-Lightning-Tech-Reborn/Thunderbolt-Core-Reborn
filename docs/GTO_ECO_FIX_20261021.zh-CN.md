# ECO / GTO 实测故障修复（2026-10-21）

> 历史记录：2026-10-21 已按要求移除本分支全部 NeoECO 专用兼容。本文中的 NeoECO 实现、用法和测试结果对应此前构建，不代表当前支持范围；当前说明见 [GTO_COMPATIBILITY.md](GTO_COMPATIBILITY.md)。

> 后续已把 NeoECO 的离线修补逻辑内置到 TB，使用原版 NeoECO 20.3.0 也已实机 16/16 通过。本文保留前一阶段修复和制品哈希，最新安装方式及 TB 哈希见 [加载期兼容报告](GTO_NEOECO_RUNTIME_COMPAT_20261021.zh-CN.md)。

**修复后真实 Forge 服务端套件 16/16 通过，启动失败 0；JVM 和测试启动器均正常返回 0。** 相关回归测试 102 项：90 通过、12 跳过、0 失败。修复前失败记录保留在 [实体测试报告](GTO_ECO_RUNTIME_20261021.zh-CN.md)。

## 生产修复

NeoECO 20.3.0 的代码将 `KeyCounter` 条目强转为 `Object2LongMap.Entry`，但实际 AE2-GTO 返回 `Reference2LongMap.Entry`。更新离线迁移工具，为经过源码审计的 8 个类迁移条目类型、方法所有者、栈帧、局部变量及泛型签名，避免仅改输入复制而在输出结算再次失败。覆盖 `ECOFastPathStacks`、`ECOFastPathKey`、`ECOCraftingThread`、工作器、存储系统、计算核心、AppFlux 存储单元集成及 CPU 菜单。其余原有 4 类迁移继续保留，NeoECO 原包合计修改 12 个类。

迁移仍锁定原始 JAR SHA-256，拒绝未知包、签名包、原地修改和覆盖已有输出。资源及嵌套依赖逐字节保留；原包不修改。新测试直接加载迁移后的官方 `ECOFastPathStacks`，用真实非空 GTO 计数器验证排序/无序复制、null 槽位、`Long.MAX_VALUE` 数量、借用输入不消费及迁移幂等性。所有支持附属的原有迁移回归也通过。

TB 的 `NeoEcoFastPathCompat.pushPattern()` 保留新版 facade 的优先级；缺少 facade 时，识别真实 NeoECO 样板总线及其子类，调用原生三参数 `pushPattern(pattern, inputs, UUID)`。其他供应器继续使用原有 AE2 普通入口。拒收不会重放，运行时异常和 Error 保留原对象向上传播。增加测试覆盖旧 facade、缺少 job-aware 接口的原生总线、UUID / null、明确拒收、异常、Error 和不相关供应器的同名重载。

## 实机结果与测试夹具修正

环境保持 Java 21.0.10、Minecraft 1.20.1、Forge 47.4.20、GTOCore 26.10.5、原包内嵌 AE2-GTO 15.2610.8、AE2LT 2.1.2-gto.1；只替换隔离实例中的 TB 和 NeoECO 迁移副本。

| 验证项 | 修复后结果 |
| --- | --- |
| 原生 ECO 多方块结构、容量与 AE 网络 | 通过 |
| 原生总线 UUID 对照组 | 真实工作器 NBT 保留相同 UUID |
| TB 普通回退 UUID | 真实工作器 NBT 的期望和实际 UUID 相同 |
| ECO 普通合成 | 1 个橡木原木 → 网络收到 4 个橡木板，无原木退款，工作线程结束 |
| 缺少精确/取消接口时的行为 | 请求 `2^126` 返回 null，工作器和输入不变 |
| AppliedE 实体 GTO CPU | 交付 65 个钻石、扣费 EMC 532480、最终库存 65、运行 64 轮 |
| 其余原有生命周期、NBT 复制及批量记账测试 | 全部通过 |

AppliedE 的历史超时来自外部测试夹具：`Poll` 将自身作为同一个 Runnable 键重新放入 GameTest 调度表，随后 GameTest 删除当前执行条目，连带删掉下一轮。改用新的回调对象转发到同一个 Poll 状态。保留 150 tick 超时、65 个产物、精确扣费和最终库存断言；没有修改 AppliedE 或 GTO CPU 生产代码。测试中的知识账户和节点使用原有代理，执行的是实际 EMC 模块实现与实体 CPU，不能扩大为完整玩家下单验收。

第一轮修复版实机为 15/16：UUID、AppliedE 等已通过，ECO 产物用例仍未收到输出。进一步核对发现测试把创造能源放在工作器外侧，而 NeoECO 只允许机器接口连接外部网络。将测试能源接到机器接口，并新增网络已供电的前置断言后，第二个全新世界 `compat-world-batch-fixed2` 得到 16/16。没有增加合成进度、手动制造产物、调用工作器 tick 替代网络调度或延长超时。

最终日志：

```text
ECO_RUNTIME NATIVE_UUID_CONTROL PASS
ECO_RUNTIME TB_UUID_OBSERVATION expected=4fad8b74-8560-4219-b84f-715988e18a0c actual=4fad8b74-8560-4219-b84f-715988e18a0c
ECO_RUNTIME REAL_OUTPUT PASS 1 oak_log -> 4 oak_planks
GTO_COMPAT_QA PASS total=16 failed=0 startupFailures=0
```

## 使用修复包

需要同时替换配套 TB 和 NeoECO 迁移副本；仅更换 TB 无法修复 NeoECO 自身的计数器遍历。客户端和服务端使用相同版本，移除同 mod ID 的旧包。

| 本地制品 | SHA-256 |
| --- | --- |
| `build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar` | `b0c8cbb4a2ddc13f04bbdf775026117583dcf1746ed3cd566692fb8bb854047f` |
| `build/compat-tools/patched-addons/neoecoae-20.3.0-gto-fixed.jar` | `f52fe23f06bc6cff138ecd62dde7351d55edbe1eeb031c8e8517511554574a01` |
| `build/compat-tools/gto-addon-patcher.jar`（离线工具，不放 mods） | `b6e8b683bf69f8d10cb29facd42a5700772e82d76aacb62596e862c86a3842cf` |

TB 与修复前冻结包相比，仅 `NeoEcoFastPathCompat` 及其 3 个内部类的字节不同，无新增/删除条目，不含测试夹具。NeoECO 与旧迁移包相比，只改变上述 8 个类及迁移清单。第三方二进制保留在本地构建输出，不提交仓库。

## 验证与证据

构建和相关测试使用独立 D 盘目录，避免覆盖此前证据及占用 E 盘空间：

```powershell
.\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false -I D:/codex-builds/thunderbolt-eco-fix-20261021/fix.init.gradle ecoFixRegression gtoAddonPatcherJar compileGameTestJava verifyReleaseJar
```

102 项相关测试中的 12 项跳过仍因缺少提供新协议的真实 NeoECO 制品；跳过项不计为通过。新 `prepareExactAllocated` facade、取消后回收及完整 CPU 生命周期不在本轮真实 20.3.0 验证范围。8 个迁移类均经过类型使用审计，但本轮实体用例没有穷举菜单、存储、AppFlux 等各条业务路径。未修改相邻 AE2LT 项目，未新增客户端 GUI 或跨 JVM 恢复验收。

`D:/codex-builds/thunderbolt-eco-fix-20261021/` 包含源码快照、测试 XML、构建日志、迁移原包/输出哈希、制品条目差异和汇总。`runtime/` 下保存独立端口 25607 实例、ECO 及 AppliedE 测试源码、构建脚本、最终测试模组、`server-batch-fixed1.log`（15/16）、`server-batch-fixed2.log`（16/16）、对应 JSON、debug 日志与 `appliede-cpu-validation.txt`。此前实例和失败日志均未覆盖。
