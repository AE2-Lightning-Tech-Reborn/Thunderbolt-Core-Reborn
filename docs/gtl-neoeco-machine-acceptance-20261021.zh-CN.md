# Forge 1.20.1 GTL：NeoECO 完整机器验收（2026-10-21）

## 结论

**本报告范围内通过。** 新版真实 NeoECO 成形机器 GameTest **4/4**，真实旧版兼容验收 **3/3**；默认 Forge 47.1.3 完整回归 **1593 通过、4 跳过、0 失败**，兼容与存储 GameTest **9/9**，发行包检查通过。所有结果均在本轮重新运行，计数按套件列出，不将重叠覆盖相加。

验收发现并修复：Forge 在给方块实体赋世界前加载 NBT，新 NeoECO 的活动工人线程直接调用空世界的 `registryAccess()`，导致原版加载器丢弃工人方块实体。生产修复仅补充这个阶段的注册表访问；没有替换机器加工、批次回执或取消行为。

本轮基于 `bcd6e8d8f6c07957850207e9382edaf856c48b91`。[上次部分验收](gtl-neoeco-acceptance-20261021.zh-CN.md)中“新源码制品未构建”的阻塞已在本轮解除。

## 真实制品与环境

| 项目 | 本轮值 |
| --- | --- |
| Minecraft / Java / AE2 | 1.20.1 / 17 / 15.4.10 |
| 新 NeoECO | 20.5.0-beta1，由[固定源码提交](https://github.com/DancingSnow0517/NeoECOAEExtension/tree/a6b3d776d677c3093954081d40d4871592864c3b)本地编译 |
| 实机测试 Forge | 47.4.20，满足 NeoECO 的 ≥47.4.0 要求 |
| 发行与普通回归 Forge | 默认 47.1.3 |
| 依赖 | 真实 Registrate、LDLib 1.0.33.b、ojAlgo 57.1.0 |
| 新开发 JAR | 5,076,284 字节，Mojang 映射 |
| 开发 JAR SHA-256 | `253b9742255a75dbbd39e0515c4b6f850ab0a6fcd5a29d76306ea52b45e6c9ea` |
| 源码审计 | 2412 个 `src/main` 文件与固定源码 ZIP 逐项一致，0 个修改 |

本轮强制执行外部项目 `compileJava`，再用 `neoEcoAcceptanceDevJar` 从真实主源集输出制作开发 JAR，没有以 `UP-TO-DATE` 代替重新编译。NeoECO 源码遵守其 GPL-3.0 许可，仅用于外部验收，不嵌入本项目发行包。官方 CI 状态及客户端映射未在本轮认定为已修复。

## 四项成形机器验证

| 用例 | 机器与实际断言 |
| --- | --- |
| 普通加工与有限批次 | 真实 L4 多方块经原生结构计算形成；铁块拆解样板预热后，适配器接收 7 份批次，共交付 72 铁锭；另一个 UUID 订单取消后返还 1 铁块，工人和控制器归零，30 tick 后无重复 |
| 超过 long 的精确批次 | 8 台满长 L9，每台 11 个工人，真实高能网络交换机与同频网络，自然满足虚拟模式资格；准备 `1180591620717411303461` 份时不派发，提交后真实 tick 加工；持久存储精确收到 `10625324586456701731158` 铁锭（含预热 9）；同量订单取消后完整返还 `1180591620717411303461` 铁块，输出稳定 |
| 活动工人 NBT 恢复 | 真实接单后保存，直接调用原版 `BlockEntity.loadStatic` 在尚未赋世界时恢复工人；活动线程保留，重新挂回世界后取消并回收 1 铁块，无产物、线程清零，30 tick 后无重复 |
| 真实 AE2 CPU 全流程 | 放置真实 64K 合成存储，给模拟请求提供真实网格节点；通过原生合成服务计算并提交至明确选择的真实 CPU，由 CPU 实际扣除 3 铁块，机器加工并交付 27 铁锭；随后提交 900 铁锭订单，在真实工人接单后调用 CPU 取消，验证 NeoECO 终态及 100 份原料单位守恒，30 tick 后稳定 |

原版样板由 AE2 编码、解码真实 `minecraft:iron_ingot_from_iron_block` 配方。测试没有注入 NeoECO 夹具类或观察 Mixin，没有强制修改配置、成形返回值、虚拟资格或派发回执。供电采用真实 AE2 创造能量单元；网格布线使用公开 `GridHelper.createConnection`。存储采用真实 `ECOInfiniteStorage` 的 SavedData 引擎，通过公开全局存储提供者接口挂载，未搭建完整存储多方块。

普通批次和大数用例使用公共独立 CPU 交接接口，传入视为已提取的一份输入模板，再显式释放输出至网络。它们验证真实机器的接收、加工、精确库存与回收，**不证明独立大数 CPU 自动下单、扣除 BigInteger 库存**。真实 AE2 CPU 的库存扣料与自动执行由第四项普通数量测试覆盖。独立 AE2 任务成功提交可返回空链接，且其内部链接没有完成 nexus；测试根据真实 CPU 空闲、NeoECO 成功终态及精确到账共同判定完成。

## 修复与回归

修复前，机器断言结束后的存盘阶段出现 `ECOCraftingThread.deserializeNBT` 空指针，随后记录 `Failed to load data for block entity neoecoae:crafting_worker` 和 `Skipping BlockEntity`。新增 `NeoEcoThreadRegistryAccessMixin`：已有世界时沿用原调用；尚未赋世界时使用当前服务器注册表；无服务器的 1.20.1 客户端静态编解码场景使用空访问器。Mixin 只在 NeoECO 存在时启用，允许旧制品或已修复制品不存在该调用。

新增真实恢复用例直接覆盖此前的丢实体触发点。验收脚本同时检查运行和保存退出日志，发现工人加载失败即拒绝通过。本轮最终 4 项通过且退出时没有上述错误；旧 NeoECO 20.5.0 也以 3/3 通过。可选 Mixin 有/无依赖的门控断言已加入完整回归。

完整 `check` 的五个测试任务均实际执行，没有复用测试结果缓存。四项跳过为：缺少 SuperMatrix 真实运行协议的 1 项，以及旧 NeoECO 未提供回执／并行能力的 3 项；它们不属于本轮 4 项新机器 GameTest。默认环境的 9 项兼容与存储测试包含 GTL 契约夹具，没有加载完整 GTL 整合包。

## 发行包

恢复默认 Forge 47.1.3 并独立执行 `verifyReleaseJar` 后通过。

`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5,425,404 字节。

```text
SHA-256: 46377485f8d2c4d623b90bf0577b404a54429119938c3af4f795f1bee9becada
```

535 个主类均为 Java 17 字节码，内嵌依赖未超过 Java 17。发行包生产 Mixin 配置和生成 refmap 一致，新注册表调用包含正确 SRG 映射；旧结果构造器与精确字节成本接口保留。不含机器 GameTest、观察 Mixin、NeoECO 生产类或夹具。

## 复现与边界

在未修改的 NeoECO 固定源码目录，使用其匹配的 Java 17、Forge 与依赖环境，应用本仓库脚本执行：

```powershell
.\gradlew.bat neoEcoAcceptanceDevJar -I "<本仓库>/tools/neoeco-source-development-jar.gradle" --no-build-cache
```

回本仓库准备真实 Mojang 开发 JAR、Registrate 和独立工作目录，再执行：

```powershell
.\gradlew.bat runGameTestServer -Pforge_version=47.4.20 -PneoEcoAcceptanceSuite=machine `
  "-PneoEcoAcceptanceJar=<新源码开发 JAR>" `
  "-PneoEcoAcceptanceRegistrate=<Registrate 开发 JAR>" `
  "-PneoEcoAcceptanceMaven=<可选本地 Maven 仓库>" `
  "-PneoEcoAcceptanceTemp=<临时目录>" `
  "-PthunderboltDevGameDirectory=<独立验收目录>" `
  -I tools/neoeco-acceptance-game-tests.gradle --offline `
  --no-parallel --max-workers=1 --no-build-cache --console=plain

.\gradlew.bat check --offline --no-parallel --max-workers=1 --no-build-cache
.\gradlew.bat verifyReleaseJar --offline --no-parallel --max-workers=1 --no-build-cache
```

完整回归中的真实旧协议测试另需提供 `-PneoecoProtocolPath=<旧 NeoECO Mojang 开发 JAR>`。兼容与存储测试用 `tools/gtl-compatibility-game-tests.gradle` 单独启用。验收与发行打包必须分别执行；机器测试脚本在结束时恢复生产资源，并要求 4 个必需用例全部通过。

本次没有完成完整 GTL 整合包、客户端 GUI、多人或长期负载验收；活动工人恢复覆盖原版 NBT 加载与重新挂载，没有宣称活动订单跨整个进程重启通过。原始日志、失败调查、世界及外部源码均留在本地忽略目录。可核对的用例、跳过明细、制品与本轮日志哈希见[结构化结果](benchmarks/gtl-neoeco-machine-acceptance-20261021.json)。
