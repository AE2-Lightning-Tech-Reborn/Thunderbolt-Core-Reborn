# 雷电核心：重生

[English](README.md)

雷电核心：重生 是 AE2 闪电科技：重生 使用的 AE2 优化与底层基础设施模组，也可以单独安装为 AE2 自动合成加速器。

当前是 **Minecraft Forge 1.20.1_GTL** 分支。如需 Minecraft 1.21.1 与 NeoForge 版本，请查看 [`main`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/tree/main) 分支。

## 环境要求

- Minecraft `1.20.1`
- Forge `47.1.3` 及以上（非 NeoForge）
- Java `17`
- Applied Energistics 2 `15.4.10`–`15.x`

客户端和服务端均需将 雷电核心：重生 与 AE2 放入 `mods` 目录。

## 主要功能

- 加速 AE2 自动合成规划，支持按顺序选择规划器，并可回退到 AE2 原生规划器
- 支持批量下发、闭环合成、时间轮调度和重载样板
- 支持精确 BigInteger 存储、可认证执行程序，以及超限合成预览
- 为合成供应器、高容量频道、索引存储单元和弹出端点提供扩展 API
- 为兼容的高容量网络提供基于最大流的频道分配（双向树种子 + 精确残量补齐）
- 包含 Advanced AE、NeoECO、AE2 Crafting Tree 和 ExtendedAE Plus 的可选兼容钩子；仅在对应模组存在时加载
- 与 GTLCore（`gtlcore`）共存：CPU 派发让位给 GTLCore，规划器挂在 `CraftingCalculation#computePlan`，其余子系统保持启用

让位边界、`-Dthunderbolt.gtlCompat` 开关与验证步骤见 `docs/gtl-core-coexistence.zh-CN.md`。

## 配置

V2 会在预算内继续优化可行方案，优先减少配方执行次数，并允许为此使用其他已有库存。
执行次数相同时，只接受不增加其他材料用量的节省方案。这是有预算限制的优化，不保证全局最优。

通用配置位于 `config/thunderbolt-common.toml`：

- `planning.enableCpSatPlanner`：启用实验性的 OR-Tools CP-SAT 规划器（默认：`false`）。启用后，Thunderbolt 会在启动时下载并校验匹配的原生运行库；加载失败不会影响其他规划器。
- `channel.mode`：控制最大流频道分配（默认：`MOD`）。`MOD` 在已加载的集成明确请求时启用，`DEVICE` 也会为主动接入的设备启用，`ON` 则在存在控制器时始终启用。

高级规划诊断和安全限制可通过 JVM 系统属性设置：

- `-Dthunderbolt.planningWarnMs=<毫秒>`：慢规划警告延迟（默认：`2000`；旧名称 `thunderbolt.watchdogMs` 仍可使用）
- `-Dthunderbolt.planningTimeoutMs=<毫秒>`：协作退出期限（默认：`3000`）
- `-Dthunderbolt.planningInterruptGraceMs=<毫秒>`：发送中断前的宽限时间（默认：`2000`）
- `-Dthunderbolt.planningStopGraceMs=<毫秒>`：超时后到隔离的总宽限时间（默认：`5000`）
- `-Dthunderbolt.maxCraftSearchWork=<数量>`：规划器搜索工作量上限（默认：`thunderbolt.maxReachablePlanningWork` 的 4 倍，即 `262144`）
- `-Dthunderbolt.maxCraftDepth=<数量>`：规划深度上限
- `-Dthunderbolt.feasibleOptimizationStallMs=<毫秒>`：可选优化连续这么久没有把执行次数减少 1% 就停止（默认：`500`；`0` 仅关闭停滞退出，不关闭外层期限）
- `-Dthunderbolt.maxConsumptionOptimizationNanos=<纳秒>`：单次计算的可选优化总时间上限（默认：`2800000000`，最小：`1000000`）；外层期限、共享工作量和探测预算仍可使其提前退出。

## 致谢

感谢 **AdUhTkJm（[AW 项目](https://github.com/AdUhTkJm/AW)）**提供的优化研究与对照案例，
为减少配方执行次数的改进提供了参考。雷电核心的有界候选仍沿用自身的库存核算与执行验证。

## 开发构建

[AWR 基准适配器](scripts/awr-benchmark/README.md) 在 Java 17 上支持 v1–v3 数据集、
加权多产物配方与显式零成本 tag 转换。

构建可分发 JAR：

```powershell
.\gradlew.bat build
```

发布到本地 Maven 仓库：

```powershell
.\gradlew.bat publishToMavenLocal
```

- 版本：`2.0.0`
- Maven 坐标：`com.moakiee.thunderbolt:thunderbolt-forge-1.20.1:2.0.0`
- 可分发 JAR：`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`

带 `-slim.jar` 后缀的 JAR 不含运行时必需的 MixinExtras 内嵌依赖，只作为开发过程的中间产物。

问题反馈：[GitHub Issues](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/issues) ·
许可证：[GNU LGPL 3.0](LICENSE)
