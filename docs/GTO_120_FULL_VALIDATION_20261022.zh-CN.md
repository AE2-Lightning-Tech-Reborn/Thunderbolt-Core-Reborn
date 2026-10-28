# 普通 1.20.1 更新对齐与全面复验（2026-10-22）

后续修复：本报告中的空矩阵槽重载缺陷及后台 GLFW 查询异常已在当前工作区修复并复验，见 [修复记录及新交付包校验](GTO_IO_CLIENT_FIX_20261022.zh-CN.md)。下文保留修复前结果，不以新结果覆盖历史失败。

本轮重新抓取普通 `1.20.1`，远端仍为 `cea4cc29b97e308987ad4a06a2c61f7fb00f0527`。可移植实现已经包含在当前工作区中，没有发现新的生产代码遗漏；没有重复覆盖现有 GTO 修复。保留 Minecraft 1.20.1 / Forge 47.4.20 / Java 21 / AE2-GTO 15.2610.8，仅以普通 `1.20.1` 为本轮更新对照，继续排除 NeoECO。本轮在本地整理和验证，未向远端推送。

## 已对齐的更新

| 普通 1.20.1 提交 | 内容 | 当前状态 |
| --- | --- | --- |
| `c41f1f7` | 有界大数订单切片、可执行报告和包协议 | 实现已包含，保留 GTO 展示及提交策略 |
| `ee79119` | 整数区间和多路线批量求解 | 6 个相关源码/测试文件与上游一致 |
| `a49351a` | 反馈环、主配方策略和需求获取顺序 | 10 个相关源码/测试文件与上游一致 |
| `ca6d2ab` | long 快速存取、精确/NBT 快照、SavedData 缓存 | 已包含，保留本地容量修复及 GTO 键身份适配 |
| `07f7883` | BigInteger 精确字节成本和诊断计划 | 已包含，保留 GTO fastutil 计划构造接口 |

`c3725a7`、`ee38e69`、`cea4cc2` 属于 NeoECO，主动排除。上游普通 AE2 的计算和 CPU 接入文件由 GTO 对应入口替代，不视为漏移植。完整逐文件结果见证据目录的 `source-audit.json`。

## 构建、单元回归与包审计

完整 `check`：1543 项，1542 通过、1 跳过、0 失败、0 错误。主套件 1520 项，另有 23 项冷启动/重放检查；全部任务实际执行。跳过项是 EAEP 开发版 Super Matrix 测试：固定的 Forge EAEP 制品没有 `ScaledMolecularAssemblerPattern`，不计为通过。

存储压力测试覆盖 4 个固定种子、共 100 万次混合 long/BigInteger 操作，独立账本验证逐键守恒；覆盖 16,384 键、512 位数量、跨代保存、压缩和 ID 复用。相关结果见主测试 XML。

`compileGameTestJava`、`verifyReleaseJar`、`git diff --check` 通过。另在全新输出目录禁用增量编译重新构建，最终 603 个 JAR 条目的内容与前次已验证的移植包全部一致。首次使用旧输出目录发现一个已无源码的 `PairBatchAllocation$Interval.class` 残留；最终交付使用干净构建，未携带该残留。许可证、署名和嵌套依赖保留；最终 Thunderbolt 包无 NeoECO 和测试夹具。

静态链接：Thunderbolt 6544 个引用、AE2LT 对 Thunderbolt 389 个引用均无未解析项。已安装的 AdvancedAE、合成树和 AppliedE 适配包同样无未解析项；该结果不替代运行时验证。

## 实际服务器

隔离端口 25611。固定使用当前干净构建及已有 `AE2LT 2.1.2-gto.1` 生产实现，不修改其他项目或日常游戏实例。NeoECO 模组和专用夹具未安装；测试副本中已移除旧的 NeoECO 专用注册及用例，未修改其他功能断言。NeoECO 的历史专用类和测试方法不以“跳过”冒充通过。

每套包含共同的 NBT/输出生命周期/AppliedE 检查，因此不把各轮数量相加作为独立用例总数。功能汇总和 JVM 退出码分别如下：

| 运行 | 功能结果 | JVM 退出码 |
| --- | --- | --- |
| `0-base` | `PASS total=18 failed=0 startupFailures=0` | 1 |
| `1-exact` | `PASS total=20 failed=0 startupFailures=0` | 0 |
| `2-batch` | `PASS total=11 failed=0 startupFailures=0` | 0 |
| `3-interfaces` | `PASS total=13 failed=0 startupFailures=0` | 0 |
| `4-io` | `FAIL total=46 failed=2 startupFailures=0` | 0 |
| `5-infinite` | `FAIL total=8 failed=1 startupFailures=0` | 0 |
| `6-infinite` | `PASS total=8 failed=0 startupFailures=0` | 0 |
| `7-content-recipes` | `PASS total=20 failed=0 startupFailures=0` | 0 |
| `save-verified-0-base` | `PASS total=18 failed=0 startupFailures=0` | 0 |
| `active-ticker-4-io` | `FAIL total=47 failed=1 startupFailures=0` | 0 |

基础首轮 18/18 通过但超过启动器 180 秒保存上限，退出码 1 保留，不算整轮通过；线程转储显示原版 RegionFile 写入及 ChunkStorage.flush 等待。后续隔离世界改用异步区块写入，保留正常停服 flush，保存等待上限 600 秒；补测结果单列。基础、精确数量及批量轮次堆上限 6 GiB；接口重跑及随后 I/O、存储升级/重启、内容轮次和基础补测使用 4 GiB，串行运行。首次接口启动受机器内存压力影响，在功能断言前主动停止，记录保存在 `memory-pressure-startup/`，不计通过。

存储升级使用上一轮实际保存世界的副本；升级和重启连续读取同一世界，检查 151 位物品、101 位流体、两段 long 数量、模拟提取不变性、提取/返还守恒及输出端注册恢复。实际供电驱动器检查 16,384 个 NBT 键、512 位数量、多个存储通道和单位换算率 8→17→8。

最终复验仍失败的功能：

- `TEST reloadingemptymatrixslotclearspreviousbonus FAIL net.minecraft.gametest.framework.GameTestAssertException: empty saved slot must replace previously installed matrices and their bonus`

AppliedE 首次在 I/O 和升级联测中失败：产物返回 65，但原生 CPU 任务未结束。无玩家隔离服的扩展套件使用 `suite=none`，旧驱动没有像基础/精确/批量套件一样设置 AE2 原生 ticker 的 `playerJoined` 状态。仅补齐夹具中的原生 tick 条件后，存储重启套件 8/8 通过；I/O 的同条件复验单列。未修改生产代码，也未放宽产物 65、EMC 精确扣款、最终库存或任务完成断言。

剩余的 `reloadingEmptyMatrixSlotClearsPreviousBonus` 失败直接验证固定依赖 AE2LT `OverloadedIOPortBlockEntity.loadTag`：先加载有矩阵的状态，再加载空槽存档，矩阵和加成没有正确清空。属于 AE2LT 2.1.2-gto.1 的存档重载缺陷，本轮没有修改其他项目源码；不能据此宣称整个整合包全部通过。

首次清理旧夹具引入的栈帧错误导致两个阶段的启动失败，均未执行功能断言；修正后重新运行，失败原始证据保存在 `fixture-frame-failure/` 和 `fixture-duplicate-frame-failure/`，不当作产品缺陷或通过结果。

## 客户端

实际进入世界，精确数量渲染、151 位数量 tooltip、终端格式及界面变换检查通过。Mixin 审计通过：启用 31 个 Mixin、覆盖 28 个目标，2 个 NBT Mixin 因交由 LeanObject 处理而跳过。客户端 JVM 正常退出，退出码 0。本轮客户端用于验证当前 Thunderbolt 的报告展示和 Mixin 加载，没有将整个整合包所有终端、配方或第三方界面声明为已穷举验证。

客户端日志另保留了退出阶段的 EMI 搜索索引异常：在 `Minecraft: Stopping!` 和渲染工作线程停止之后，EMI 后台线程为部分 GTCEu 机器生成 tooltip，调用 `GTUtil.isShiftDown` / GLFW 时出现空指针。该日志不影响此前功能断言及退出码，但也不将整合包客户端描述为全程无错误。

## 产物与证据

交付包：`build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`。

SHA-256：`237fd7f458c3908bc33888658272a58f127642c7746a134855774516b851cba4`。

证据位于工作区 `build/review/upstream120-20261022/`（现有 build 联接下），包括完整检查日志和单元汇总、干净构建日志、源码/发布包/静态链接审计、服务器矩阵、补测及客户端日志；原始开发环境日志和测试副本均不加入版本提交。

复现构建：

```powershell
.\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false -I build/review/upstream120-20261022/full.init.gradle check compileGameTestJava verifyReleaseJar
.\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false -I build/review/upstream120-20261022/clean.init.gradle compileGameTestJava verifyReleaseJar
```
