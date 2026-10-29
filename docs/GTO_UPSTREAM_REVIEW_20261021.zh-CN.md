# GTO 上游检查（2026-10-21）

> 后续已完成本文两个候选的选择性移植及验证，README 残留也已清理，见 [移植与实测](GTO_120_PORT_20261021.zh-CN.md)。下文保留检查时状态。

已联网查询 GitHub 分支并执行 `git fetch upstream --no-tags`，刷新远程跟踪引用。本轮只进行源码对比，未合并、移植、提交或更新发布包。检查包含当前工作区未提交和未跟踪源码，忽略 CRLF/LF 差异。

Git 全局代理 `127.0.0.1:7888` 当前连接失败；本轮通过单条命令的 `-c http.proxy= -c https.proxy=` 直连成功，未修改全局配置。

## 远程状态

| 分支 | 最新提交 | 相比此前缓存 |
| --- | --- | --- |
| `gto` | `7c7a066af9d404010fe0b9472fca687879b36281` | 无新增，本地 HEAD 相同；工作区已有其他改动 |
| `1.20.1` | `cea4cc29b97e308987ad4a06a2c61f7fb00f0527` | 从 `a49351a` 增加 4 个提交 |
| `1.20.1_GTL` | `78c1b160272947915889a935f0ba1489adfe5437` | 从 `8ad2d57` 增加 5 个提交 |
| `main` | `bb2f2ab0ad48ef3b53087327fe833d4e1743eb98` | 无新增 |
| `alpha` | `28f5f8f3fcc33f384ce4383e5a2af8f4b00e05a0` | 包含精确字节成本改动及主线合并 |

## 可参考改动

1. **大数合成报告字节成本**：[Forge `07f7883`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/07f7883fb3dd5c35649abbf3185ef8df35330846)，GTL 对应提交 `656a1c9`。`BigCraftingPlanner.Result` 新增诊断计划，并保留旧三参数构造器；把预览原有成本公式提取到 `ExactCraftPlan.byteCost(...)`，让报告能读取中间加工需求、容器/工具成本及超过 long 的精确成本。当前本地 Result 未保留诊断计划，ExactCraftPlan 也无该公共方法；预览本身已有 BigInteger 公式，不能概括为所有现有预览成本均错误。适合优先单独移植，保留 GTO 预览构造与返回对象约定。GTL 另补了旧构造器契约测试，可一并参考。
2. **无限盘完整存取和保存快照优化**：[Forge `ca6d2ab`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/ca6d2ab40b85f35c792421f9ee3aae89b7ab063b)，GTL 此前已有 `fdd766c`。涉及 long 存取路径、容量维护、`ExactCellSnapshot`、`CellNbtSnapshot`、保存快照复用，以及服务器停止时清除缓存。当前本地只移入了换算率失效检测等正确性修复；`hasCurrentUnits()` 及库存同步检查均在，但尚无两个快照类和完整优化。该组改动涉及存档与异步保存所有权，应整体评估，并验证精确数量守恒、有限盘容量、模拟操作、旧存档读取与重启恢复。上游测试结果不等于当前 GTO 组合已通过。

## NeoECO 与分支边界

上游 GTL 最新 [78c1b16](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/78c1b160272947915889a935f0ba1489adfe5437) 同样移除 NeoECO 兼容。此前新增的普通任务 UUID、精确准备/取消及工作器 NBT 修复均属于用户已明确排除的范围；当前分支保持移除。

普通 `1.20.1` 仍包含 NeoECO，不能整分支合并。GTL 编译基线仍为普通 AE2 15.4.10 / Forge 47.1.3 / Java 17，当前 GTO 为 AE2-GTO 15.2610.8 / Forge 47.4.20 / Java 21，亦不能整文件覆盖全部集成。

检查还发现本地 `README.md:32` 和 `README_zh_CN.md:24` 遗留 NeoECO 兼容描述，与已清理源码不一致。本轮记录该文案遗漏，未修改源码或 README。

## 比较证据

`D:/codex-builds/thunderbolt-upstream-review-20261021/` 保存 `remote-heads.txt`、`working-status.txt`、`comparison.json` 和比较脚本。逐文件扫描 `src/`、`scripts/`、`build.gradle`、`gradle.properties`，包括未跟踪文件。

| 上游分支 | 相同 | 不同 | 仅上游 | 仅本地 |
| --- | --- | --- | --- | --- |
| `gto` | 435 | 38 | 6 | 24 |
| `1.20.1` | 424 | 51 | 21 | 22 |
| `1.20.1_GTL` | 331 | 126 | 49 | 40 |
| `main` | 250 | 181 | 38 | 66 |
| `alpha` | 248 | 183 | 39 | 66 |

缺少的 NeoECO 文件为主动删除；普通 AE2 计算和 CPU 接入文件也可能由 GTO 专用入口替代。上述数量用于定位差异，不能作为漏移植或兼容性结论。本轮未运行测试，原发布包及上轮验证结果保持原状。
