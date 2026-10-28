# GTO 全面测试（2026-10-20）

> 历史记录：2026-10-21 已按要求移除本分支全部 NeoECO 专用兼容。本文中的 NeoECO 实现、用法和测试结果对应此前构建，不代表当前支持范围；当前说明见 [GTO_COMPATIBILITY.md](GTO_COMPATIBILITY.md)。

> 后续更新：通道换算率缓存修复已移植，40 项存储回归通过，实际无限盘用例通过；最新交付包和未解决项见 [换算率移植实测](GTO_UNIT_RELOAD_PORT_20261020.zh-CN.md)。以下保留此前阶段的结果。

后续与最新远程上游的逐项比较见 [GTO_UPSTREAM_COMPARISON_20261020.zh-CN.md](GTO_UPSTREAM_COMPARISON_20261020.zh-CN.md)。其中确认 GTL 新提交已提供通道换算率失效修复；尚未移入本测试包，不改变下述实测失败结果。

本轮实际测试已完成，整体未全量通过。主测试 1502 项中 1487 通过、13 跳过、2 失败；已修复 CP-SAT UNKNOWN 回退，另保留冷启动不稳定和运行时缺陷。服务端、客户端、真实 JVM 重启、静态链接及频道压力测试均有实测证据，具体边界如下。

| 验证 | 最终记录 |
|---|---|
| 原有冷启动任务 | 23/23 通过 |
| 额外五轮冷 JVM | 23/25 通过 |
| 基础 / exact / batch / interfaces | 18/18、21/21、12/12、13/13；正常保存退出 |
| 扩展 I/O / 无限存储 / 内容配方 | 45/47、6/8、19/20；正常保存退出，失败保留 |
| 客户端 | 启动、精确合成表、EMI 通过；终端清缓存失败；JEI 布局未验证 |
| 真实重启 | 精确库存、模拟/返还与输出注册恢复通过 |
| 频道 | 5000 随机 oracle、边界、9 类拓扑对照通过 |
| 发布包 | 编译/包检查通过，无测试探针；当前未达到全量测试通过 |

本次验证当前脏工作区的完整实现。运行环境为 Java 21.0.10、Minecraft 1.20.1、Forge 47.4.20、GTOCore 26.10.5 及其内嵌 AE2 15.2610.8。构建、独立游戏实例、临时文件和原始证据存放于 `D:/codex-builds/thunderbolt-full-qa-20261020`。

## 本次发现与修复

原生 CP-SAT 返回 UNKNOWN 时，已有可执行的纯 Java 预搜索方案可能被后续的缺料回退覆盖。现在优先保留已经验证的方案，同时保留原生超时对应的预算耗尽标记，不声称获得全局最优证明。新增 `CpSatTimeoutFallbackTest`，替换隔离桥接的求解方法以确定性模拟 UNKNOWN，断言原生路径确实调用一次、方案可执行、无缺料、库存消耗正确且预算标记保留；测试结束恢复原桥接。

另按上游计时方式适配当前 GTO 的小图预搜索：无调用者截止时间时使用线程 CPU 时间；绑定调用者、外层可选阶段和原生求解仍遵守实际截止时间。未整体替换 GTO 算法。

## 构建与单元测试

已交付包位于 `build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`，与下述冻结测试包哈希一致。重新构建的 ZIP 时间戳可不同，逐条目内容比较为零差异；最终 `compileGameTestJava verifyReleaseJar` 成功，发布包 610 个条目，不含测试探针或 GameTest 类。

冻结修复包：`tested-fixed-release.jar`，SHA-256 `090d9708ea6773be8f4eb000a9314266f1b0c6f4e4b74f545d6185bc44eec12a`。原测试包哈希 `69de6fd0618d9634b88cc9b88548fbcd9c4adb229e075137886a2785b2c1d108` 及失败日志一并保留。

完整检查主套件 1502 项：1487 通过、13 项可选协议跳过、2 失败。原有冷启动任务 23/23 通过；额外五轮、每类独立 JVM 的检查 23/25 通过。`verifyReleaseJar` 及优化基准完成。完整 `check` 返回非零，不能描述为全量通过。

- `MaterialDagSupplyTest.linearSideOutputSchedulingCoversGraphsBeyondTheFormerSixteenThousandWorkCap`：4096 分支超过原有 3 秒限制。
- `CraftPlannerV2Test.partialFeedbackLoopKeepsOneRecoveredBatchAsBootstrapSeed`：完整运行得到 `{C=2}` 而非预期 `{C=1}`。
- 额外冷启动测试的第 1、2 轮：方案被标记预算耗尽，未通过“不耗尽”的原断言。第 3～5 轮通过。

隔离 JVM 补测上述两个主套件失败及首次失败的数千独立分支选择，共 3/3 通过，断言和时限未改动。保留首次失败与完整运行记录，不以补跑覆盖完整结果。机器同时存在多个其他工程的构建和游戏进程，未停止它们；本次不能提供无竞争环境的稳定延迟保证。

## 运行时与兼容性验证

真实 JVM 重启验证已通过：151 位物品数量、101 位流体数量、两段 long 的累计数量、模拟提取不变性、提取返还平衡、仅保留未关闭的输出注册，以及运行时索引恢复。实际世界生成了 `thunderbolt-full-qa-restart-pass.txt`。

基础套件在全新世界 18/18 通过。复用世界的 CPU 掉落夹具曾因地面物品残留得到 12 而非预期 7；该用例本身不是可重复执行的存档夹具，功能矩阵改用全新世界，保留重启数据验证和原失败记录。

服务端矩阵以功能断言和 JVM 退出结果分别记录。基础、exact、batch、interfaces 均已通过，分别为 18、21、12、13 项；每套包含输出注册生命周期、LeanObject 兼容和 AppliedE 实体 CPU 探针，不能把各套的共同检查重复累计为独立用例。基础套件首次保存等待仅 60 秒，功能 18/18 通过但启动器超时终止；将保存等待改为 180 秒后，在全新世界再次 18/18 通过并正常退出。线程转储显示区块文件写入及 `ChunkStorage.flush` 等待，未发现 Thunderbolt 锁死证据。

扩展 I/O 首轮实际执行 47 项，45 通过、2 失败：空矩阵槽存档覆盖后保留旧加成，以及 AppliedE CPU 在同一游戏 tick 连续驱动后的完成状态。内容和配方首轮 20 项中 19 通过，唯一失败也是该 CPU 探针。无限存储首轮在调用测试源码中的新版 AE2LT `readSummary` API 时中断，未取得完整结果。后续补测记录见本报告末尾。

客户端启动、Thunderbolt 151 位合成确认表的实际 renderer 与完整 tooltip、EMI 结构界面的绘制范围/旋转/缩放/隐藏外壳均通过。所有进入验证阶段的客户端均成功加载 31 个启用 Mixin 对应的 28 个不同目标；2 个 NBT Mixin 按 LeanObject 兼容规则停用。

终端库存测试未通过，且补测确认并非两秒等待不足：服务端完成提取后精确库存为 37，客户端原生库存投影也为 37，但其精确显示缓存仍保持 `10^100 + 12345`，直到原有 1600 tick 上限。失败发生在原生终端的清缓存阶段，因此同一脚本后续的精确值恢复及天枢终端阶段未完成，不能声称两种终端全部通过。本次没有发现该过程的库存数量丢失。

JEI 布局初次启动失败由共同加载的 `BigTerminalClientProbe` 静态创建 AEKey 引起，过早触发 GTO 材料表初始化。隔离测试包仅将该探针改为进入世界后延迟初始化；逐条目比较确认仅改变其测试类，AE2LT 生产类未更新。之后的 JEI 探针又将 EMI 的 `JemiRecipesGui` 强转为 JEI `RecipesGui`，这属于测试入口不匹配。实际 EMI 路径已有独立通过记录；纯 JEI 补测单独记录，不将夹具异常当作产品布局缺陷。

客户端退出阶段仍出现第三方 JemiPlugin runtime null 和 ModernUI dead-thread 异步异常，未将其描述为已修复。

测试环境修正记录：首次启动器从共享基础实例自动补入 `gto.2` AE2LT，造成精确版本依赖冲突；已移除独立实例中的重复包并关闭自动补包，固定使用 `gto.1`。原日志归档于 `harness-mixed-version-failure`。

NBT 原版注入夹具不适用于整合包内嵌的 LeanObject：Thunderbolt 主动禁用自己的 NBT copy Mixin，原测试要求 HashMap 和融合收集器导致 3 项失败。其中手工替换内部容器触发 LeanObject 的类型转换。原失败保存在 `nbt-original-fixture-failure`；改用独立兼容夹具验证注入确实停用，以及原生容器下的深拷贝、数组隔离、异常后恢复和随机图等价。原有测试断言没有被削弱。

## 静态链接

纳入服务端、客户端 Minecraft 库和实际模组后：Thunderbolt 6570 个引用、AE2LT 对 Thunderbolt 的 392 个引用均无未解析项。迁移附属 AdvancedAE 1811、合成树 65、AppliedE 411、NeoECO 801 个引用均无未解析项。仅服务端库的初始审计存在 9 项 Minecraft 客户端或继承的 SRG 引用，补全客户端库后消除；静态审计不替代运行时验证。

本次不扩大 ExtendedAE Plus 的支持声明；AppliedE 仍需适用的 Thunderbolt V2 规划路径；NeoECO 20.3 的普通派发支持不等于实现新版 FastPath 协议。


## 测试工具与版本适配记录

原始日志和失败输出均保留，补测未覆盖首次失败：

- `server-*-final.log`、`server-*-supplemental2.log`：首轮与扩展首轮；`matrix-progress.json` 保存完整原矩阵。
- 扩展测试最初缺少旧 QA 包的 suite 入口；改为手动注册。首次追加夹具还触发测试包之间的 JPMS split package，已把新增夹具重命名至 `thunderboltfullqa.fixtures`，保留失败启动日志。
- 无限存储夹具来自更新的 AE2LT 源码，`gto.1` 无 `readSummary`。适配版直接读取既有 `ae2lt:types`/`ae2lt:bytes` 摘要，继续检查数量、键集、模拟操作、保存与网络往返。新版“写摘要不复制 NBT 对象”的内部优化断言不适用于 `gto.1`，明确改为检查实际持久化摘要值；不能据此声称新版优化通过。
- AppliedE 原探针在同一游戏 tick 内驱动 CPU 100 次，出现间歇性“输出已返回 65、任务仍在完成中”。补测版改为最多 100 个真实游戏 tick，保留 65 输出、EMC 精确扣款和最终库存 65 的断言，未改变生产实现。
- 频道旧版本对照源码首次因 Windows 默认编码写出而无法编译；驱动已显式使用 UTF-8，该次属于夹具构建失败。

## 证据与执行入口

原始证据目录：`D:/codex-builds/thunderbolt-full-qa-20261020`。`results.json` 汇总主套件、各轮补测、运行日志、发布包、客户端结果与真实重启标记；`final-check.log` 保留完整检查非零结果；`fallback-before-after.log` 与 `before-fix-regression` 保留 UNKNOWN 回退修复的同一回归测试前后对照。`isolated-regressions.log` 是原断言不变的隔离补测。

构建入口：`gradlew.bat --offline --no-build-cache --max-workers=1 -I <证据目录>/full.init.gradle check compileGameTestJava verifyReleaseJar fullQaCold1 fullQaCold2 fullQaCold3 fullQaCold4 fullQaCold5 optimizationBenchmark writeFullQaClasspath`。隔离运行驱动为证据目录中的 `run_server.py`、`run_client.py` 和 `run_channel.py`；它们固定测试包及依赖副本，重型测试串行执行，不修改共享整合包实例。

本次结论仅适用于上述固定版本与运行配置；尚未验证真实多人并发或长时间生产服负载。完整 `check` 仍有失败，不能作为全量通过或稳定性保证。


## 频道正确性与规模测试

独立最大流 oracle 的 5000 个固定种子随机图全部通过；并行路径/共享瓶颈、无线与物理连接、多方块、不可传输节点、无限容量、重复容量、断开重连及 8/32/128 容量边界均通过。9 类拓扑、当前与基线实现共 18 个场景全部完成且流量符合断言。每个性能场景预热 2 次、采样 3 次；以下是本机竞争负载下的中位时间，仅用于此次对照，不能当作稳定延迟承诺。

| 场景/参数 | 实际节点 | 连接 | 流量 | 当前 ms | 基线 ms |
|---|---:|---:|---:|---:|---:|
| tree/1000 | 1266 | 1265 | 1000 | 3.220 | 3.666 |
| comb/1000 | 2266 | 2265 | 1000 | 3.014 | 159.101 |
| chain/20000 | 20267 | 20266 | 1 | 12.840 | 24.608 |
| tree/136192 | 226898 | 226897 | 136192 | 273.175 | 575.487 |
| mesh/136192 | 272650 | 408368 | 136192 | 406.670 | 18163.992 |
| finite/136192 | 272650 | 408368 | 34048 | 423.879 | 3122.480 |
| dense/136192 | 226898 | 226897 | 136192 | 260.119 | 574.441 |
| vanilla/136192 | 226898 | 226897 | 136192 | 282.575 | 564.612 |
| weighted/512 | 532 | 531 | 136192 | 1.429 | 2.559 |

原始校验和样本位于 `channel/oracle.log`、`channel/results.json`、`channel-supervisor-fixed.log`；旧实现取自 `49233dceba0f0d34b0b7baf78e66b41fc42d3138`，与当前实现使用相同图和检查。


## 最终扩展补测与未通过项

固定使用原 AE2LT `2.1.2-gto.1` 生产类，补测仅更新独立测试探针。无限存储压力测试已执行 16384 个带 NBT 的物品键、多个活动盘和闲置盘保存、精确数量账本、模拟与实际出入库及通用键往返；在检查通道换算率热变更时失败：测试通道每字节数量由 8 改为 17 后，`IndexedStorageCellInventory.usedBytes()` 仍返回旧换算率的缓存值。当前 `ensureSync()` 仅按存储 `modCount` 重建，无法识别这种没有库存修改的换算率变化。该功能未通过，恢复换算率后的后续阶段也不能计为已通过。

同轮无限存储套件共 8 项，6 通过、2 失败，另一失败为 AppliedE 实体 CPU 超时。I/O 补测仍为 47 项中 45 通过、2 失败（矩阵空槽重载、AppliedE 超时）；内容和配方补测为 19/20 通过，唯一失败仍为 AppliedE CPU 超时。跨 tick 的 CPU 探针仍在原 GameTest 150 tick 上限内超时，实际完成逻辑与夹具调度关系未完全定位，因此不能把首次 65 个输出已返回当作任务完成证明。

当前已确认需要后续修复或继续定位的内容：

1. 完整主单元套件的两项失败，以及额外冷 JVM 中两轮预算标记失败；隔离通过不能抹去这些失败。
2. 原生终端精确库存缓存未随普通库存降至 37 而清除。
3. 自定义通道换算率变化时，已有库存处理器的字节统计缓存不失效。
4. 固定 AE2LT `gto.1` 的 I/O 端口加载空矩阵/过滤器存档后保留旧状态；外部 AE2LT 当前源码已有加载前清空容器的实现，本次未升级其生产包，不能据此声称固定组合已修复。
5. AppliedE 实体 CPU 扩展场景的完成状态与超时，保留为未解决项。

本轮生产代码修复仅包括前述 CP-SAT UNKNOWN 回退和 witness 计时适配；上述其他问题没有在报告中标记为修复。测试未全量通过。


纯 JEI 配置补测：仅在 D 盘独立客户端暂时移出 EMI 与其强依赖 EMITrades；客户端在进入世界前退出（进程退出码 1，无布局结果），日志存在整合包其他 EMI Mixin 的缺失目标。没有足够证据把此次退出归因于 Thunderbolt 布局功能。测试结束已恢复两个原始 JAR，原整合包 EMI 路径通过记录保留；JEI 布局仍列为未验证。详见 `jei-only-result.json`、`client-layout-jei-only-console.log`。

最终服务端和客户端补测状态分别保存于 `supplemental3-exits.txt`、`supplemental-client-channel.json` 及 `jei-only-result.json`，汇总读取原始日志而不覆盖首轮 `matrix-progress.json`。
