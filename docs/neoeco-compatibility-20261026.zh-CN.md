# NeoECO Forge 1.20.1 适配与验证（2026-10-26）

本次按明确的 ECO 兼容要求，在 `1.20.1_GTL` 的 `57eda50` 基础上接入 NeoECO 20.5.1。
参考 [NeoECOAEExtension / 1.20.1](https://github.com/DancingSnow0517/NeoECOAEExtension/tree/1.20.1)
的 `d1e306c7fbf557fc883f8c804b0d10c22b057f89`，并验证官方发行 JAR。
保留 GTL CPU 接管、既有规划器修复和默认 Forge/AE2 依赖版本；`AGENTS.md` 未修改。

适配由 common setup 按 `neoecoae` 和公开接口注册 `thunderbolt:neoecoae`，通过反射避免强制依赖。
原生 `IBatchCraftingProvider` 优先，因此不会重复包装 ECO 的大型工作站。
GTL 接管激活时仍跳过可选批处理注册，由 GTL/ECO 的原生 CPU 路径负责派发。

批处理使用 `ECOFastPathFacade.prepareAllocated`，仅接受接口批准的无状态 NORMAL 配方且无返还输入。
CPU 持有物理材料、能量和等待产物的账本；ECO 使用零能量私有账本准备接收。
准备捕获 UUID 和输入副本，缩小供给时重新准备；明确拒绝可回退单份普通派发，
提交异常则停止任务，避免不明所有权下退款或重复投料。另提供保留 `BigInteger` 的 exact 辅助接口与取消入口。
exact 调用方必须独立持有完整事务，普通 Core CPU 不因此自动获得超 long 派发能力。

## 环境与来源

| 检查 | 环境 |
| --- | --- |
| 默认回归与发行构建 | Minecraft 1.20.1、Forge 47.1.3、AE2 15.4.10、GuideME 20.1.15、Java 17.0.20、Windows amd64；无 ECO |
| 真实 ECO 机器 | Forge 47.4.20、AE2 15.4.10、GuideME 20.1.15、NeoECO 20.5.1、LDLib 1.0.50、Java 17.0.20；运行时选择 ECO 嵌套的 MixinExtras 0.5.3 |
| GTL 回归 | 默认依赖、`thunderbolt.gtlCompat=always` 与仓库既有 GTL 注入夹具；未加载完整 GTLCore 模组 |

NeoECO 20.5.1 的发行元数据要求 Forge `[47.4.10,48)`、AE2 `[15.4.10,16)`、
GuideME `[20.1.14,20.2.0)` 与 LDLib `[1.0.33.b,1.1)`。共装时须满足这些要求。
其源码推荐的 Forge 47.4.28、AE2 15.4.11、LDLib 1.0.52.a 不等同于本轮实际测试版本。

官方文件：[neoecoae-20.5.1.jar](https://cdn.modrinth.com/data/udZtKfzP/versions/vjWvlG5t/neoecoae-20.5.1.jar)，
7650126 字节，SHA-512 已与 Modrinth `vjWvlG5t` 的记录逐字校验：

```text
8a4e5d9b36c311acb17c476977d65a58a05e8c3600fb69985c2158e98f9788f05bed52d5f4fbe8f331eb2639f5459e792c7a553f7691696bb777ff257f179344
```

NeoECO 由 DancingSnow0517 等上游作者维护，许可为 GPLv3。这里调用其公开接口，使用原创隔离协议夹具；
上游源码和发行包只用于验证，不嵌入 Thunderbolt 的 LGPL 发行包。

## 验证结果

完整 `check assemble verifyReleaseJar` 成功；7 个测试任务均强制执行，1582 次通过、1 项跳过、0 失败/错误。
次数包含独立 JVM 的重复覆盖，不能作为去重用例数。唯一跳过项为既有 EAEP 1.5.5 缺少
`ScaledMolecularAssemblerPattern` 的可选协议测试。

| JUnit 任务 | 通过 | 跳过 |
| --- | ---: | ---: |
| `test` | 1515 | 1 |
| `externalPlannerBoundaryTest` | 38 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 |
| `cpSatColdRefinementTest` | 1 | 0 |
| `materialDagColdReplayTest` | 20 | 0 |
| `materialDagColdReplenishmentTest` | 2 | 0 |
| `planningLifecycleTimingTest` | 5 | 0 |

新增的 16 项协议测试包含真实 AE2 `BatchExecutor` 和库存账本，检查部分接收、准备失败、拒绝、
异常后停止、能量只结算一次、输入隔离、UUID 快照、跨 tick 提示失效、缺少依赖及原生提供器优先。

真实 Forge GameTest 结果分列：

| 套件 | 通过 | 覆盖 |
| --- | ---: | --- |
| 官方 ECO 实际机器 | 4/4 | 组装机器的普通/7 份批次产出与取消守恒；AE2 CPU 真实规划、扣料、收货和取消；活动工作线程在未赋世界前的原版 NBT 重载；8 台最大长度 L9 的 exact 执行与取消 |
| 默认 NBT | 5/5 | 默认模式、Mixin 审计启用 |
| GTL 与 NBT | 13/13 | `always` 模式、存储、端点和生命周期回归 |

ECO 机器测试启用 Mixin 审计，检查真实 common setup 注册。虚拟模式通过正常设置启用超频与主动冷却，
从真实输入仓注入冰晶溶液并使用正常冷却配方；exact 数量为 `2^70 + 37`，检查完整产出、取消返还和重试后无重复。
四项均通过后服务器完成世界保存和退出，无工作线程加载失败或丢弃日志。

早期尝试曾因旧夹具接口、重复 Registrate、模板命名空间和未启用新版虚拟模式而失败，均在夹具/启动配置中修正。
完整 JUnit 的首次尝试被旧结果文件锁阻止，改为隔离结果目录后成功；没有放宽测试断言、数量或限时。
Minecraft 客户端资源索引下载返回过截断 JSON，专用服务端验证使用缓存依赖并排除 `downloadAssets`。

LDLib 1.0.50 在专服扫描 ECO `PatternItemSlot` 时仍记录一次被捕获的 `LocalPlayer` 客户端类错误；
该日志来自上游控件扫描，未阻止初始化或四项机器测试，不应把本轮结果描述为启动日志完全无错误。

## 复现与产物

使用 Java 17。在仓库根目录执行默认检查：

```powershell
.\gradlew.bat --no-parallel --max-workers=1 check assemble verifyReleaseJar
.\gradlew.bat runGameTestServer
.\gradlew.bat -I tools/gtl-compatibility-game-tests.gradle runGameTestServer
```

实际 ECO 机器检查与发行打包分开执行；提供官方 ECO 和兼容 LDLib JAR，路径按实际位置替换：

```powershell
.\gradlew.bat --no-parallel --max-workers=1 -I tools/neoeco-compatibility-game-tests.gradle '-Pforge_version=47.4.20' '-PneoEcoTestJar=build/neoecoae-20.5.1.jar' '-PneoEcoTestLdlibJar=build/ldlib-1.0.50.jar' '-PthunderboltDevGameDirectory=build/neoeco-machine-tests' runGameTestServer
```

验收入口自动将发行包转换为开发映射。Registrate 仅额外提供编译 API，运行时使用 ECO 的嵌套副本，避免重复模块。
本轮原始日志、临时世界与隔离结果保留在忽略的 `build/neoeco-compat-20261026/` 及本项目专用验证目录中。

发行包为 `build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5420427 字节，SHA-256：

```text
621b20c4b9c7fbc4ec1f739cb4f1f61339dccd89ca006f97d15dc52496c94afd
```

533 个主类均为 Java 17 字节码；Mixin manifest/config/refmap 检查通过。
包中包含 8 个自有 ECO 适配类，无 NeoECO 类型的硬链接描述符、强制依赖、上游实现或验收夹具，
没有打包 ECO、LDLib 或 Registrate。GTL 测试结束后生成的 Mixin 配置已恢复生产配置。

本轮未覆盖完整 GTLCore 整合包共装、图形客户端、多人联机、长期 TPS；AdvancedAE CPU 与大型工作站的完整实际机器也未验收。

[结构化结果](benchmarks/neoeco-compatibility-20261026.json)
