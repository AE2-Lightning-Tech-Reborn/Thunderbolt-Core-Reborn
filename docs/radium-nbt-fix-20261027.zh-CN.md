Core / Radium 默认 NBT 注入冲突修复（2026-10-27）

Core `2.0.3-beta4` 已在固定 Radium `0.12.4+git.26c9d8e` 的默认 NBT 配置下通过 5 项真实 Forge GameTest；关闭 Radium NBT 模块、未安装 Radium 的两个独立对照也各通过原有 5 项测试，合计 15/15。未关闭 Core 的默认 NBT 优化，没有修改 Radium JAR 或其默认规则。正式完整 GTL 客户端的默认配置恢复验收仍需 AE2LT 使用新包继续执行。

源基线为 `1.20.1_GTL` 的 `68bb94496f49c81212fa88d1eb714de91ca96edf`。保留 NeoECO，未修改其他项目或 `AGENTS.md`，既有未跟踪文件保持原样。所有构建和运行在本项目的 C 盘隔离副本进行，原 beta3 制品没有被覆盖。

根因是 Mixin 注入权限校验：Core 的 `CompoundTagCopyMixin` 优先级 900，尝试向 Radium 优先级 1000 的 `NbtCompoundMixin` 已覆盖的 `CompoundTag.copy()` 注入 `WrapOperation`。校验发生在寻找调用点之前，所以 `require=0` 和处理函数内部的 `NbtCopyOptions.enabled()` 都不能避开失败。使用旧 Core 源码和交接中的精确 Radium JAR，在隔离 Forge 环境中重新复现了相同异常；该轮未进入测试，退出 1，日志单独保留。

检查实际 Radium 字节码可见，它的覆盖方法仍调用 `Maps.transformValues`，但使用 `Object2ObjectOpenHashMap` 构造结果。此次将 CompoundTag Mixin 优先级调整到 1100，允许向该覆盖方法注入现有包装调用。Core 原有的精确 `source.getClass() == HashMap.class` 判断会为 Radium 的 fastutil 容器调用 original；Radium 的 collector 保留，未重新实现它的复制逻辑。`ListTagCopyMixin` 及其优先级、所有复制处理函数、运行期配置和 Mixin 选择插件均未改变。

本次兼容验证针对上述固定版本及覆盖结构。实际转换后的 `CompoundTag.copy()` 包含 Core 的 transform wrapper 和 Radium 的 fastutil 构造器，保留 Radium collector；实际 `ListTag.copy()` 仍调用 Core 的 transform 和 collector 两个 wrapper。真实测试同时检查了复制次数、容器类型和开关路径。

| 验证 | 环境 | 结果 |
| --- | --- | --- |
| 原始失败复现 | beta3、Radium 默认 NBT、Forge 47.4.20 | 注入阶段失败，0 项测试执行；与交接的 Forge 47.4.16 客户端错误一致 |
| 新版 + Radium 默认 NBT | beta4、全新配置，Radium 日志为 0 overrides | 新增真实兼容 GameTest 5/5，通过并正常保存、退出 |
| 新版 + Radium NBT 关闭 | 仅独立对照世界设置 `mixin.alloc.nbt=false` | 原有 NBT GameTest 5/5；Core 原有工厂/collector 路径断言不变 |
| 新版、无 Radium | 未加载 Radium init 脚本 | 原有 NBT GameTest 5/5；Core 原有工厂/collector 路径断言不变 |

三组 GameTest 使用 Minecraft 1.20.1、Forge 47.4.20、AE2 15.4.10、GuideME 20.1.15、Java 17.0.20+8、Gradle 8.14，按顺序在独立 JVM 和世界中运行，GTL 接管为 `never`。日志均含 `All 5 required tests passed`、`All dimensions are saved`、`Game test server shutting down`，Gradle 退出码均为 0。默认 Radium 组的配置文件只有生成的说明注释，没有关闭 NBT 的设置；关闭组只作为对照。

新增默认 Radium 组具体覆盖：

- Core NBT 开关 false/true 下，CompoundTag 和 ListTag 每个子项均恰复制一次；Radium map collector 保留，Core 列表融合只在启用时执行。
- 嵌套 compound/list、byte/int/long 数组的副本与源对象分离，空值容器和不可变标签共享语义不变。
- 自定义 `LinkedHashMap`、`LinkedList` 不进入 Core 的原生 collector，仍正确复制。
- `Tag.copy()` 原始异常向上传播，失败后重试没有残留状态；两种 Core 开关均覆盖。
- 固定种子 `0x52414449554d` 的 128 组深层图，两种开关下内容及哈希一致；真实压缩 NBT 写入、读取后，副本修改不影响内存原件或再次从磁盘读取的值。

随后在默认 Forge 47.1.3 构建环境执行 `check assemble verifyReleaseJar generatePomFileForMavenJavaPublication --continue`，退出 0，耗时 220.579 秒。所有 7 个 JUnit 任务强制实际执行，并校验 XML 时间属于本轮；共 1583/1583 通过、0 失败、0 错误、0 跳过。EAEP 1.6.3 只通过既有测试参数加入 JUnit classpath，其桥接 3 项已包含在总数中。旧诊断任务的残留 XML 单独归档，不计入本轮总数；冷态 JVM 的重复执行沿用此前全面报告的统计口径。

| JUnit 任务 | 执行与通过 |
| --- | ---: |
| `test` | 1516 |
| `externalPlannerBoundaryTest` | 38 |
| `materialDagLargeGraphTest` | 1 |
| `planningLifecycleTimingTest` | 5 |
| `cpSatColdRefinementTest` | 1 |
| `materialDagColdReplayTest` | 20 |
| `materialDagColdReplenishmentTest` | 2 |

新发行包为 [thunderbolt-forge-1.20.1-2.0.3-beta4.jar](../build/libs/thunderbolt-forge-1.20.1-2.0.3-beta4.jar)，5420478 字节，SHA-256：

```text
374e5f5035601b6191d1f9dac117b5e6c676f1afa81846595d8d7d77c896cb75
```

同目录提供 `.jar.sha256` 校验文件。Maven 坐标为 `com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1-gtl:2.0.3-beta4`；已生成并核对 POM，没有发布 Maven 包或推送。发行 JAR 的版本、manifest、refmap 与本轮生成结果匹配，533 个主类均为 Java 17，保留原有嵌套依赖与许可证，没有打入 GameTest、Radium 或 EAEP 测试依赖。交接中提到的 `NbtCopyOptions` 与 `EjectRegistrationSavedData` 两个类均存在，但存在性不等于已定位其先前运行期解析失败的原因。

与之前 beta3 隔离发行包比较，所有主类条目集合相同。`CompoundTagCopyMixin.class` 为本次实际修改；另一个 `FastCraftingPlanner.class` 仅编译器生成的局部调试变量名从 `patt59414$temp` 变为 `patt60554$temp`。完整方法反汇编相同，且只归一化该名称后两份 class 其余字节全部一致，源码未改。原始差异保存在 `radium/class-comparison/verbose.diff`。

交接固定 Radium 文件 SHA-256 为 `b42584e2672d6b5329959ec2b0f342395b19389fed0ee4d86ea71072f42f77a0`。新增 [init 脚本](../tools/radium-compatibility-game-tests.gradle)在启动前强制核对文件名和哈希，仅显式加载时添加 runtime 依赖及验证 sourceSet，并拒绝与发行打包任务一起请求。NBT 模板复制自本仓库原有空模板；测试源码与模板不属于发行 sourceSet。

默认 Radium 组可从本仓库用 PowerShell 复现，须使用独立新测试目录；Gradle 和游戏 JVM 按顺序运行：

```powershell
$radiumJar = 'C:\qa\radium-mc1.20.1-0.12.4+git.26c9d8e.jar'
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 --console=plain `
  '-Pforge_version=47.4.20' "-PradiumTestJar=$radiumJar" `
  '-PthunderboltDevGameDirectory=C:\qa\core-radium-default' `
  -I tools/radium-compatibility-game-tests.gradle runGameTestServer
```

关闭 NBT 的对照使用另一个独立新目录，仅在该目录的 `config/lithium.properties` 设置 `mixin.alloc.nbt=false`，并传 `-PradiumTestNamespace=thunderbolt_nbt`，执行原有五项测试。无 Radium 对照不加载上述 init 脚本，执行默认 `runGameTestServer`。不应将关闭组配置用于正式默认配置验收。

完整证据保存在 `%LOCALAPPDATA%/Temp/thunderbolt-gtl-full-20261027/`：`radium/baseline-real.log` 为原始错误复现，`radium/enabled.log`、`radium/disabled.log`、`radium/standalone.log` 为三组结果，各有独立 `*-run.json` 命令、耗时和退出码；对应世界在 `games/radium-*`。导出类、`radium/enabled-CompoundTag.javap.txt`、`radium/enabled-ListTag.javap.txt` 和 `radium/transformed-bytecode.json` 保留实际注入证据。第一次封装命令因 PowerShell 将未引用的版本参数拆分而失败，保存在 `radium/baseline.log`；改为 JSON 参数列表后才执行有效复现，不把参数解析错误算作产品失败。

本仓库没有修改 AE2LT 的实例、配置或性能预算，也没有重跑完整 GTL 客户端。下一步由 AE2LT 在其复制实例替换新 Core，恢复 Radium 默认配置，执行 NBT 深拷贝、合成与取消守恒、正常保存后同存档独立 JVM 重启验收。交接中两次运行期缺类异常、此前规划器间歇探针失败的根因以及 AE2LT 未过的性能预算，均不能据本次 NBT 修复宣布已解决。

变更文件为 `CompoundTagCopyMixin.java`、版本属性和 README、`src/radiumGameTest/` 下的 Java 与 NBT、可选 Radium init 脚本及本报告与[结构化结果](benchmarks/radium-nbt-fix-20261027.json)。环境原始日志不加入提交，未推送。
