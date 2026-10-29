# 当前 GTO 与上游对比（2026-10-20）

> 历史记录：2026-10-21 已按要求移除本分支全部 NeoECO 专用兼容。本文中的 NeoECO 实现、用法和测试结果对应此前构建，不代表当前支持范围；当前说明见 [GTO_COMPATIBILITY.md](GTO_COMPATIBILITY.md)。

> 后续更新：通道换算率缓存修复已移植，40 项存储回归通过，实际无限盘用例通过；最新交付包和未解决项见 [换算率移植实测](GTO_UNIT_RELOAD_PORT_20261020.zh-CN.md)。以下保留此前阶段的结果。

本次联网核对远程分支，并更新 Thunderbolt 的远程跟踪引用。比较对象包含当前未提交和未跟踪源码；没有合并、移植、提交或重新打包。此前实机测试结论仍见 [全面测试报告](GTO_FULL_TEST_20261020.zh-CN.md)。

最有价值的新发现是：上游 `1.20.1_GTL` 已新增 Forge 1.20.1 无限盘优化，其中明确修复了本地实测发现的通道换算率缓存失效问题。此前“只有 1.21.1 优化可参考”的判断已不适用于最新上游。

## 分支基线

| 对象 | 核对后的提交 | 与当前项目的关系 |
|---|---|---|
| 本地 HEAD / 上游 `gto` | `7c7a066af9d404010fe0b9472fca687879b36281` | 提交相同；实际工作区有额外改动 |
| 上游 `1.20.1` | `a49351af36081ebf39fefe4903628bc1a5c06332` | HEAD 层面本地独有 1 个 GTO 提交、该分支独有 4 个提交；部分内容已通过未提交改动移植 |
| 上游 `1.20.1_GTL` | `fdd766c07e4352e60516f4f0c75aa279f6113f49` | 本次从原 `fd3db3f` 更新，新增无限盘优化 |
| 上游 `main` | `6caa5aaca1693cb14dc6fe23b2125a50975ad225` | Minecraft 1.21.1 / NeoForge 21.1.219；包含较新的 NeoECO 互通修复 |

上游当前没有发布 `perf/infinite-cell-reviewed` 分支引用，本地还留有旧跟踪引用；本次没有将其当作最新发布分支，也没有删除本地引用。

## 已对齐与必须保留的差异

| 内容 | 当前状态 | 判断依据 |
|---|---|---|
| `1.20.1` 规划器优化 | 基本对齐 | `core/crafting/planner` 下 81 个生产源码文件中 77 个内容相同，换行统一后比较 |
| 整数区间、反馈分析、主产物规划 | 已对齐 | `IntegerInterval`、`CraftPlannerV2`、`ConservativeFeedbackAnalysis`、`FeasibleConsumptionOptimizer`、`MultiRouteBatchOptimizer`、`PairBatchAllocation`、`TwoRouteBatchOptimizer` 均与 `1.20.1` 相同 |
| 规划器剩余 4 个文件 | 有意保留差异 | `FastCraftingPlanner`、`ExactPlanPreview` 适配 GTO 模拟状态和 fastutil 构造参数；`CpSatRankedFlowSolver`、`PlanningCancellation` 包含本轮 UNKNOWN 回退修复和 witness 计时适配 |
| 频道算法 | 已对齐 | 频道目录 6 个生产源码文件与上游 `gto`、`1.20.1` 均相同；此前对旧实现的性能基准不表示当前频道代码比这两个上游分支更新 |
| 容量边界与输出端注销 | 已移植 | `fd3db3f` 的容量借位、剩余单位、类型上限及准确注销逻辑已在工作区；当前 `ByteTracker` 与该提交内容相同；输出端保留 GTO 的 `persist` 约定 |
| 摘要、完整数量和合成树组合 | 已按 GTO 适配 | 参考 `a305f6f`；GTL 专用接口和 `gtlcore$planLongAmountJob` 不适用于当前 GTO |

Git 的提交数不能直接表示移植完成度：这些移植尚在工作区，不能仅用 ahead/behind 判定“落后 4 个提交”。同样，Git 默认 diff 不显示未跟踪新文件，本次文件清单已将其纳入。

## 与全面测试失败逐项核对

| 实测问题 | 上游对应情况 | 下一步参考价值 |
|---|---|---|
| 通道每字节数量由 8 改为 17，已有库存字节统计不更新 | [`fdd766c`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/fdd766c07e4352e60516f4f0c75aa279f6113f49) 已加入 `ByteTracker.hasCurrentUnits()`，并在 `IndexedStorageCellInventory.ensureSync()` 中检查；含 `ByteTrackerUnitReloadTest` | **优先移植**；与本地失败机制直接对应，但尚未在当前 GTO 组合上验证该修复 |
| CP-SAT UNKNOWN 覆盖可执行预搜索方案 | 上游 `gto`、`1.20.1` 仍先返回 `smallPartialWitness`，后检查 `preflight` | 当前本地修复应保留；GTL 求解路径结构不同，不能整文件覆盖 |
| 完整测试的大图超时及循环种子断言失败 | 对应的两个测试文件与 `1.20.1` 相同，相关 `CraftPlannerV2` 实现也已对齐 | 未找到仅靠继续移植这些规划优化即可解决的依据；本轮没有执行上游完整套件，不能宣称上游同样失败或已经修复 |
| 原生终端库存已为 37，但精确显示缓存仍保留超大值 | AE2LT 上游 `gto` 的 `BigTerminalStock`、菜单 Mixin、包处理源码与相邻 GTO 工作区相同，没有发现对应的已提交 GTO 修复 | 继续定位 GTO 菜单同步/包应用链；Thunderbolt 无限盘优化不能直接视为此问题的修复 |
| I/O 端口加载空矩阵/过滤器快照仍保留旧内容 | AE2LT 上游 `gto` 和 `1.20.1_GTL` 的 `loadTag` 都没有先清空容器；相邻 AE2LT 工作区已添加 `filter.clear()`、`matrix.clear()`，但尚未提交 | 属于相邻工作区已有修复，不能称为远程上游已发布修复；固定 `gto.1` 实机包没有该改动 |
| AppliedE 实体 CPU 超时 | 本次检查的相关上游提交没有提供经当前 GTO 实机验证的对应修复 | 保留未解决状态；NeoECO 的任务归属修复不能当作 AppliedE 超时修复 |
| JEI 布局探针失败 | 已确认夹具假定原生 JEI 内部界面，与当前 EMI/JEMI 界面不符；纯 JEI 配置在入世界前退出 | 未找到可直接归因于 Thunderbolt 布局实现的证据，仍列未验证 |

AE2LT 远程 `gto` 已核对为 `cb80004f75af0fdde12e974312d5f0a3667f05fe`，`1.20.1_GTL` 为 `d6152742571690a0af06aea7cb8b7701f54d0e74`；这两个本地远程跟踪引用与远程一致。本次只读相邻项目，没有改动其文件或引用。

## 尚未移植的候选

1. **优先：`fdd766c` 的换算率失效检测及回归测试。** 当前 `ByteTracker`、`IndexedStorageCellInventory`、`IndexedCellStorageSavedData` 与优化前 GTL 基线对应文件相同，参考路径直接。可以先提取正确性修复；完整优化还涉及生产端 7 个文件，包括存储布局、精确快照、NBT 快照、保存数据和服务端停止清缓存，应作为一组评估。
2. **随后：`fdd766c` 的 long 存取和保存快照优化。** 上游已完成 1.20.1 NBT/Forge 保存生命周期适配，无需从 1.21.1 DataComponents 重新开始。但其验证环境是 Java 17 / Forge 47.1.3 / AE2 15.4.10，当前项目是 Java 21 / Forge 47.4.20 / AE2-GTO 15.2610.8；上游报告的通过数与性能数据不能代替当前整合包实测。尤其要保留有限盘 126 位模拟边界、共享库存身份和现有容量回归。
3. **条件性参考：主线 [`9cb676b`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/9cb676b9a3d78a9cb4539c3f9603cb227b9bb6c7)。** 为 NeoECO 普通回退保留订单 UUID，增加精确 FX 准备和取消通知。当前适配层没有相应入口，但固定 NeoECO 20.3 缺少该新版 API，使用普通派发；需先确认目标依赖支持，不能直接照搬 NeoForge 实现。

新版 GTL 移植说明和测试来源：[上游无限盘移植报告](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/blob/fdd766c07e4352e60516f4f0c75aa279f6113f49/docs/gtl-infinite-cell-port-20261020.zh-CN.md)。其中的测试结果为上游报告，本轮只做源码核对，没有重新执行。

## 对比证据

`D:/codex-builds/thunderbolt-upstream-compare-20261020/comparison.json` 保存分支完整 SHA、逐文件状态及本地文件 SHA-256。扫描范围为 `src/`、`scripts/`、`build.gradle`、`gradle.properties`，包含未跟踪文件，忽略 CRLF/LF 差异；文档和构建产物不计入文件数量。

| 上游分支 | 内容相同 | 内容不同 | 仅上游存在 | 仅本地存在 |
|---|---:|---:|---:|---:|
| `gto` | 443 | 36 | 0 | 25 |
| `1.20.1` | 440 | 43 | 6 | 21 |
| `1.20.1_GTL` | 342 | 123 | 49 | 39 |
| `main` | 250 | 185 | 34 | 69 |

`1.20.1` 中本地缺少的 6 个文件均为原生 AE2 计算/CPU Mixin 或 accessor；当前 GTO 已采用不同入口，不能仅按文件缺失判断为漏移植。分支 diff、远程 heads 和工作区状态保存在同一证据目录。此前测试包保持不变，本次比较没有使任何未通过项转为通过。
