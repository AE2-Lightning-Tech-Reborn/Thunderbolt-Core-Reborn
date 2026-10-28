# 空矩阵槽重载与客户端按键查询修复（2026-10-22）

本轮修复 [全面验证报告](GTO_120_FULL_VALIDATION_20261022.zh-CN.md) 中的 LT 空矩阵槽重载失败和客户端退出阶段 GLFW 查询异常。修复位于当前 Thunderbolt 工作区，没有修改其他项目目录、AE2LT/GTO 原包或 AGENTS.md；继续排除 NeoECO，未推送远端。

## 空矩阵槽重载

AE2-GTO `AppEngInternalInventory.readFromNBT` 按存档条目覆盖槽位，不清除没有条目的旧槽位；空库存保存时会删除对应标签。LT `OverloadedIOPortBlockEntity.loadTag` 再次读取空矩阵存档时，因此保留旧矩阵及吞吐加成，过滤槽也有同类问题。

新增可选 `LtIOPortInventoryLoadMixin`，只在 LT 超载 I/O 端口读取矩阵和过滤槽的两个调用处补全空槽默认值，再交给原来的读取操作。保存中的有效条目覆盖默认值；不修改传入 NBT，不触发物品变更事件、保存或网络唤醒。原有吞吐、冷却、物品 NBT 和丢弃规则继续由 LT 处理。

单元测试覆盖缺失标签、显式空列表、稀疏多槽库存、物品 NBT 保留、重复槽及越界条目的原生处理语义，并验证不会触发库存变更事件。Mixin 仅在安装 `ae2lt` 时启用。

## GLFW 查询

GTCEu `GTUtil.isShiftDown/isCtrlDown/isAltDown` 原来在客户端直接调用 `Screen` 的原生按键查询。EMI 后台生成提示文本时也会经过这些入口，窗口销毁后继续查询会产生 GLFW 空指针。

新增 Forge 方法转换器 `gto_modifier_keys.js`，保留原有物理客户端判断，只替换三个方法中的 `Screen` 调用。`ClientModifierKeys` 在客户端主线程且客户端仍运行时执行原查询；后台或关闭后返回未按下。专用服务端不会走入客户端辅助类。没有屏蔽 EMI 日志、捕获并吞掉异常或替换第三方类。

转换器测试实际执行转换后的字节码，验证三个入口、服务端不解析客户端辅助类、客户端分支调用以及目标签名变化时的失败行为。实际客户端导出的 `GTUtil` 字节码也确认了三个调用已替换。

补齐客户端夹具后，进一步复现 MoreMoreLang 从 EMI 后台直接调用 `Screen.hasShiftDown` 的同类问题。客户端新增 `ScreenModifierKeysMixin`，在三个共享入口使用同一线程及生命周期判断，覆盖绕过 GTUtil 的提示文本监听器；前台运行状态仍执行原生按键查询。客户端专项夹具直接测试 GTUtil 与 Screen 两条路径。

## 最终验证

环境继续使用 Minecraft 1.20.1 / Forge 47.4.20 / Java 21 / GTOCore 26.10.5 / AE2-GTO 15.2610.8 / AE2LT 2.1.2-gto.1。

| 验证 | 结果 |
| --- | --- |
| 完整 `check` | 1549 项：1548 通过、1 跳过、0 失败/错误 |
| `compileGameTestJava`、`verifyReleaseJar` | 通过 |
| 专用服务器最终 I/O 套件 | 47/47 通过，JVM 退出码 0 |
| 原失败 `reloadingEmptyMatrixSlotClearsPreviousBonus` | 通过 |
| 实际客户端前台/后台按键查询 | 通过；GTUtil 三个入口及 Screen 三个直接入口，各进行 100 次后台查询 |
| 客户端 JVM 关闭钩子中的查询 | 通过；GTUtil 和 Screen 两条路径均无 GLFW 异常 |
| 客户端精确数量渲染、tooltip、进入世界及界面变换 | 通过，JVM 退出码 0 |
| 客户端 Mixin 目标检查 | 33 个启用 Mixin、30 个目标；2 个 NBT Mixin 交由 LeanObject 处理 |
| 补齐模板后的客户端附加服务器夹具 | 3/3 通过，无夹具启动失败 |

跳过项仍为固定 Forge EAEP 制品缺少 `ScaledMolecularAssemblerPattern` 的可选测试，未计为通过。服务器最终使用的 JAR 与交付 JAR 字节完全相同。测试夹具和 NeoECO 均未打入生产包。

## 保留的失败与验证边界

客户端新增夹具第一次因与旧夹具共用 Java 包导致模块冲突，在执行断言前退出，已改用独立包。早期按键 Mixin 方案的目标加载审计虽通过，实际导出字节码和关闭查询证明注入缺失；已删除该方案及其编译残留，最终使用方法转换器。两类失败日志均保留，不计为通过。

客户端测试副本的服务端 QA 驱动曾因缺少 `runtime_empty.snbt` 记录启动失败，这是附加服务器夹具的失败，不能算成客户端全部测试通过。后续已补齐隔离副本的结构模板。客户端目标断言、专用服务器套件与附加夹具结果分别记录。

补齐模板后的第一次追加运行，测试监督脚本输出含无效字符的日志时触发 GBK 编码错误，未能取得该轮 JVM 退出码；该轮功能及关闭查询标记保留，不当作完整通过。监督脚本已明确使用 UTF-8 和替换错误字符后重跑。该轮同时复现的 MoreMoreLang 调用路径促成上述共享入口修复。

历次客户端日志还出现 GTO 异步数据初始化失败（重复 `gtceu:liquefaction_furnace/extract_air` 配方，或 `GTOLoots.DYNAMIC_LOOT` 为空）、由此引发的 EMI 配方加载错误、JEI/EMI 停服重载竞争，以及 ProjectE 的联网证书检查失败。GTO 数据加载错误在按键转换器加入之前也已出现，本报告对应的客户端日志仍有数据加载及 EMI 重载错误；本轮没有修改其实现，不能将本次两个修复项通过扩大为整个整合包全部配方和界面无异常。后续初始化时序修复与新产物见 [GTO 数据初始化兼容记录](GTO_DATA_INITIALIZATION_FIX_20261022.zh-CN.md)。

## 产物与证据

交付包：`build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`。

SHA-256：`f185b5ad2d4174aba870ad64f2d1b17b4b26e38311e2d51b7859fffd0d375ffb`。

本轮证据位于 `build/review/fix-20261022/`：最终完整回归日志 `final-check.log`、单元汇总 `unit-summary.json`、服务器结果 `server-results.json`、客户端日志、实际导出字节码 `transformed-gtutil.txt`、产物审计及本轮实测冻结包。原始开发日志和测试副本不加入版本提交。
