# 雷电核心：重生

[English](README.md)

雷电核心：重生 是 AE2 闪电科技：重生 使用的 AE2 优化与底层基础设施模组，也可以单独安装为 AE2 自动合成加速器。

当前是 **Minecraft Forge 1.20.1 GTO** 分支。如需 Minecraft 1.21.1 与 NeoForge 版本，请查看 [`main`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/tree/main) 分支。

## 环境要求

- Minecraft `1.20.1`
- Forge `47.4.20` 及以上（非 NeoForge）
- Java `21`
- GTOCore `26.10.9` 及以上（与 NeoECO `1.20.1-GTO` 最低要求一致）
- AE2-GTO `15.2610.31` 起、低于 `16`；编译基线为 `15.2610.31`

从 `2.0.3-gto.2` 起，GTOCore 要求为 `[26.10.9,)`，AE2 为 `[15.2610.31,16)`，对应 NeoECO 的 GTO `0.6.0.23` 目标环境。版本范围是加载要求，不代表范围内所有组合均通过实测；LT 仍须使用对应环境的配套构建。旧 `gto.1` JAR 保持原有依赖要求。详见 [GTO 兼容说明](docs/GTO_COMPATIBILITY.md)。

客户端和服务端均需在上述配套环境的 `mods` 目录安装 雷电核心：重生，保留整合包原配 AE2。

NeoECO 可选支持针对 [`20.5.0-gto.1`](https://github.com/Yang120231/NeoECOAEExtension/tree/1.20.1-GTO)。使用它时必须整组升级至对应 GTO 基线；旧 AE2LT `2.1.2-gto.1` 和 AppliedE `0.14.3-gto` 的依赖声明不接受该组合。详见 [GTO NeoECO 兼容记录](docs/GTO_NEOECO_GTO_20261026.zh-CN.md)。

## 主要功能

- 加速 AE2 自动合成规划，支持按顺序选择规划器，并可回退到 AE2 原生规划器
- 支持批量下发、闭环合成、时间轮调度和重载样板
- 为合成供应器、高容量频道、索引存储单元和弹出端点提供扩展 API
- 为兼容的高容量网络提供基于最大流的频道分配
- 包含 Advanced AE、AE2 Crafting Tree、ExtendedAE Plus 和 NeoECO GTO 的可选兼容钩子；仅在对应模组存在时加载

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

- 版本：`2.0.3-gto.2`
- Maven 坐标：`com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1:2.0.3-gto.2`
- 可分发 JAR：`build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.2.jar`

带 `-slim.jar` 后缀的 JAR 不含运行时必需的 MixinExtras 内嵌依赖，只作为开发过程的中间产物。

问题反馈：[GitHub Issues](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/issues) ·
许可证：[GNU LGPL 3.0](LICENSE)
