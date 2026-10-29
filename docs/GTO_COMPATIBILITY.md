# GTO 1.20.1 兼容构建

## 当前最低版本要求

从 Thunderbolt **`2.0.3-gto.2`** 起，最低要求与 NeoECO `1.20.1-GTO` 同步：**GTOCore `26.10.9`、AE2-GTO `15.2610.31`**。编译依赖固定为 AE2 `15.2610.31`，继续使用 Forge `47.4.20`、Java `21`；目标环境参考 NeoECO 的 GTO `0.6.0.23` 实例。

| 组件 | 当前要求 |
| --- | --- |
| Minecraft | 1.20.1 |
| Java | 21 |
| Forge | `[47.4.20,)` |
| GTOCore | `[26.10.9,)` |
| AE2-GTO | `[15.2610.31,16)`，编译基线 15.2610.31 |
| Thunderbolt | 2.0.3-gto.2 |
| AE2LT | 使用支持同一 GTOCore / AE2 组合的构建，单独验证 |

要求依据为 NeoECO 提交 [`cca7fbb`](https://github.com/Yang120231/NeoECOAEExtension/blob/cca7fbb71b9dc922029880e25fc35f65ceb71fc5/src/main/resources/META-INF/mods.toml) 的依赖声明及其 [GTO 适配说明](https://github.com/Yang120231/NeoECOAEExtension/blob/cca7fbb71b9dc922029880e25fc35f65ceb71fc5/docs/compat/GTO_ADAPTATION_ZH_CN.md)。此次同步不降低本项目的 Forge/Java 要求。

整包版本是目标环境，加载器按 GTOCore/AE2 的声明范围检查。范围内的组合仍须单独验证；旧 Core `26.10.5` / AE2 `15.2610.8` 不再满足本次要求。旧 `gto.1` JAR 的元数据保持原样，不会因更新源码说明而改变。

历史记录：对旧 `gto / 7c7a066` 交付包的专服复测为 94 项中 89 通过、5 失败，另有缓存同步探针失败；该结果不能替代本次整合分支的验证。此次在本地 `1.20.1_GTL` 已有新版 GTO 修复基础上同步最低要求，保留既有兼容代码；LT `gto.9` 联合验收仍需使用配套制品单独执行。

本次验证：以 AE2 `15.2610.31` 编译，`build compileGameTestJava` 成功，1561 项自动检查中 1560 通过、1 项因未提供可选 EAEP 实件而跳过，无失败。最终重混淆发行包的依赖插值、Mixin refmap、嵌套依赖及许可证检查通过。

以本次固定发行包，在 GTOCore `26.10.9` / AE2 `15.2610.31` / NeoECO `20.5.0-gto.1` / Forge `47.4.20` / Java `21` 隔离专服中正常加载，11 项真实 API/批量协议探针通过，进程退出码 0。探针包括受控 provider 的所有权、拒收、异常、能量与数量边界，不等于全部真实机器加工或 LT 联合验收；本轮未重测图形客户端。证据位于工作区 `build/review/eco-minimum-gto/`。

交付包：`thunderbolt-forge-1.20.1-2.0.3-gto.2.jar`；SHA256：`fd7b8bbfa607e6553c77655787fb128d9824811970247b1a3a4060efe19b5331`。

## 先前更新与验证记录

2026-10-26 按用户新要求接入指定 `1.20.1-GTO` 分支的 NeoECO `20.5.0-gto.1`，增加 GTOCore `26.10.9` / AE2-GTO `15.2610.31` 基线。新兼容直接调用公开 API，并修复新版 GTO 混淆异步执行器的初始化跟踪；版本组合、测试和限制见 [本轮兼容记录](GTO_NEOECO_GTO_20261026.zh-CN.md)。以下旧记录保留其当时的验证范围。

2026-10-22 继续修复 GTO 客户端异步生成数据与首次数据包加载的竞态，保留原有后台执行器，在 Forge 加载完成阶段等待实际任务完成。完整自动检查 1556 通过、1 跳过，实际专用服务器 I/O 47/47 通过；两次正式客户端实测均确认 EMI 就绪和五张奖励袋战利品表保留，JVM 均以 0 正常退出。实现、实测结果及产物校验见 [GTO 数据初始化兼容记录](GTO_DATA_INITIALIZATION_FIX_20261022.zh-CN.md)。

2026-10-22 已修复下述复验发现的 LT 空矩阵槽重载及客户端后台 GLFW 查询问题。最终自动检查 1548 通过、1 跳过，实际服务器 I/O 47/47 通过；客户端后台及关闭后的按键查询通过。修复方式、其他整合包日志问题与新产物校验见 [修复及复验记录](GTO_IO_CLIENT_FIX_20261022.zh-CN.md)。

2026-10-22 已重新对齐普通 `1.20.1` 至 `cea4cc2`（排除 NeoECO 更新），完成干净构建、1543 项自动检查及实际服务器/客户端复验。自动检查 1542 通过、1 跳过；服务器 I/O 仍有 1 项 AE2LT 空矩阵槽重载缺陷。完整结果、限制和产物校验见 [最新全面验证报告](GTO_120_FULL_VALIDATION_20261022.zh-CN.md)。

2026-10-21 已继续移植上游 `1.20.1` 的 `ca6d2ab` 无限盘存取/快照优化及 `07f7883` 大数报告成本保留，保留 GTO 容量修复并排除 NeoECO。构建、完整检查和旧存档升级实测见 [本轮移植记录](GTO_120_PORT_20261021.zh-CN.md)。

本分支基于 Reborn `1.20.1`，用于 GTO 整合包，不是普通 AE2 版本。

2026-10-21 曾按当时要求移除全部 NeoECO 专用兼容：批量/任务桥接、加载期转换器、离线迁移、专用依赖和测试。2026-10-26 的指定 GTO 版支持范围以上述新记录为准；此前 ECO 报告仅保留为历史证据。清理时的回归与实际服务器结果见 [移除与验证记录](GTO_NEOECO_REMOVAL_20261021.zh-CN.md)。


2026-10-20 继续移植上游 GTL 的公共容量核算、输出端注销和第三方摘要兼容修复；来源、GTO 差异及实测记录见 [本轮移植说明](GTO_UPSTREAM_PORT.zh-CN.md)。

已同步上游 `1.20.1` 至 `a49351a` 的通用改进：有界精确订单切片、可执行精确数量报告、整数区间求解，以及正反馈分析和主配方规划优化。保留本分支 Java 21、GTO 依赖锁定与原生计算回退，并修复候选算法超时中断被误判为用户取消的问题。

精确数量报告的网络格式随本次同步更新，客户端与服务端须使用同一构建。

2026-10-19 的进一步检查修复了闭环计划包装后的报告读取，以及旧 LT 可执行摘要被标为仅预览的问题。**普通版可选附属仍存在 AE2-GTO API 失配**，包括本项目测试依赖中的 AdvancedAE、AppliedE、ExtendedAE Plus 和 AE2 Crafting Tree；批量适配器存在并不代表这些普通版 JAR 可用于 GTO。具体版本和实测结果见[兼容性检查报告](GTO_COMPATIBILITY_AUDIT.zh-CN.md)。

## 历史配套版本（gto.1，不安装 NeoECO）

本表及旧实例复现步骤保留为历史依据；`gto.2` 的当前要求以上表为准。

| 组件 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Java | 21 |
| Forge | 47.4.20 |
| GTO 整包基线 | 0.6.0-dev15，公开 0.6.0 包提交 `3650c7d330ade1b744ade0bc303fe5111eac98fa` |
| GTOCore | 26.10.5 |
| AE2-GTO | 15.2610.8（使用 GTOCore 26.10.5 原包内嵌版本） |
| Thunderbolt | 2.0.3-gto.1 |
| AE2LT | 2.1.2-gto.1 |

客户端与服务端一起替换对应的 TB/LT JAR，移除同 mod ID 的旧版本。GTOCore、GTOLib 与 GTO 自带 AE2 均使用原件；无需修改 GTO。不要把这两个包放进普通 AE2 整合包，也不要把普通 LTPP/其他依赖旧 AE2 API 的附属兼容性视为本次验证范围。

## 不支持混用最新 AE2 源码依赖

公开 GTOCore 26.10.5 原件的 `CreativeCellInventoryMixin` 仍要求 `configured: java.util.Set`。外置 AE2-GTO 15.2610.10 已将其改为 `AEKeySet`，该组合会在 GTO 自身的客户端 Mixin 中失败。旧基线使用内嵌的 15.2610.8；当前基线须同时升级 GTOCore 至最低 26.10.9 和 AE2 至最低 15.2610.31。旧版曾同时允许两组精确版本，但不能交叉混装；`gto.2` 已移除旧基线。

## 适配边界

- TB 在 GTO 的合成提交入口捕获库存快照，在其已有工作线程上执行候选算法。候选算法不适用时，调用原有 GTO 计算任务并保留其返回对象及元数据。取消、候选失败和 CRAFT_LESS 继续经过 TB 的有界执行器。
- GTO 已删除的 AE2 模拟库存类迁入 TB 自己的包，保留 LGPL 声明，不向 `appeng` 包补回旧类。
- 普通实体 CPU 任务使用 GTO 原生优化实现。含 LT 超载样板的任务使用 LT 自有调度器，并保留实体 CPU 的容量、协处理器预算、暂停、存档、CraftingLink 身份与产物核算。通过 AE2 CPU 集群安装 GTO 公共 CPU 类的子类，不注入或改写 GTO 类。
- LT 的 JEI 处理器迁入自有包；更新 GTO 的 GenericStack、菜单、数量显示、FastCollection 与 CraftingPlan API。
- 直接挂载的 BigMEStorage 保持 BigInteger 精度。经存储总线等连接其他 ME 网络时，使用 GTO 原生去重查询；这条跨网络路径的单次操作和可见数量受 `Long.MAX_VALUE` 限制。不能把多个指向同一库存的连接当作多份库存。
- AE2-GTO 原生物品键忽略仅 ForgeCaps 不同的身份差异；此构建沿用该语义，不承诺恢复普通 AE2 的 capability-only 区分。
- Forge 方法核心转换器为 GTCEu `ChemicalHelper.getMaterialEntry` 的已核对签名加同步锁，防止附属并行初始化时材料缓存扩容越界；不打包替代 GTCEu 类。LT 直接创建的大数摘要也会补全 Crafting Tree 的显示载荷，保留精确数量和可执行状态。
- 客户端首次数据包加载会等待 GTO 原有异步配方、战利品和 EMI 数据任务完成，防止提前清空战利品暂存表。初始化失败保留原始原因并终止启动；专用服务端保持同步初始化。
- 已检查版本的 AdvancedAE、AE2 Crafting Tree 需使用离线迁移副本，见 [附属迁移工具](GTO_ADDON_MIGRATION.zh-CN.md)。AppliedE 0.14.3 可使用迁移副本，但必须选择 Thunderbolt V2 规划；其固定样板转化和实体 GTO CPU 交付已实测通过。ExtendedAE Plus 仍未完成迁移；具体边界见 [兼容性检查报告](GTO_COMPATIBILITY_AUDIT.zh-CN.md)。

## 构建

使用 Java 21。先构建 TB 并发布到其工作树本地 Maven 仓库：

```sh
./gradlew check jarJar verifyReleaseJar publishMavenJavaPublicationToMavenRepository
```

再在 LT 仓库构建：

```sh
./gradlew check jarJar
```

LT 默认从同级 `../Thunderbolt-Core-Reborn/repo` 查找配套 TB。目录不同时传入 `-Pthunderbolt_local_repository=/绝对路径/repo`。

LT 的 GTOCore 编译依赖、版本及原件校验以所选 LT 分支的构建配置为准。旧版 LT 的 `26.10.5` 固定依赖和哈希不适用于新基线。本项目从 GTO Maven 编译依赖 AE2 `15.2610.31`，不把 AE2 或 GTOCore 嵌入发行包。

分发 `build/libs` 中没有 `-slim`、`-sources`、`-gto-test-agent` 后缀的主 JAR。单元测试用 javaagent 仅将 AE2-GTO 的基础身份 mixin 应用于没有 ModLauncher 的 JUnit JVM，不是生产依赖。

## 实机验证复现

LT 保存了可选测试源集 `src/gtoQa`：

```sh
./gradlew -PgtoQa gtoQaJar
```

将 `build/gto-qa/gto-compat-qa.jar` 与本次两个主 JAR 放入**隔离测试用** GTO 服务端/客户端。测试模组会在服务端启动后于 `(0,100,0)` 附近放置测试结构并自动运行用例；客户端进入世界后检查界面与数量格式、保存截图并自动退出。不要放入正常游戏存档。它不包含在 TB/LT 主 JAR 中。

基础服务端套件需出现 CPU、STORAGE、PLANNER 三个 PASS，以及 `GTO_COMPAT_QA PASS total=11 failed=0 startupFailures=0`；客户端需出现 `GTO_CLIENT_QA PASS`。测试覆盖真实 Mixin 后的计算入口、原生回退、共享库存去重、大数库存、挂载顺序、实体 CPU 超载投料/暂停/存档恢复/变体返回/普通任务切换，以及时轮状态和共享种子循环。大数订单和批量套件通过 `-Dae2lt.gtoQaSuite=exact` 或 `batch` 单独选择。最新实测结果及客户端验证范围以兼容性检查报告为准；这些测试不等于整个整合包所有配方和所有可选附属的穷举测试。

## 来源

- GTO 整包：[GregTech-Odyssey 0.6.0 基线](https://github.com/GregTech-Odyssey/GregTech-Odyssey/tree/3650c7d330ade1b744ade0bc303fe5111eac98fa)
- GTOCore 源码：[GTOCore-Main](https://github.com/GregTech-Odyssey/GTOCore-Main)
- AE2-GTO 源码：[Applied-Energistics-2-gto](https://github.com/GregTech-Odyssey/Applied-Energistics-2-gto)
- 迁入的 AE2 LGPL 类见各仓库 `THIRD_PARTY_NOTICES.md`。

最新全面测试与已知不稳定项：[GTO_FULL_TEST_20261020.zh-CN.md](GTO_FULL_TEST_20261020.zh-CN.md)。
