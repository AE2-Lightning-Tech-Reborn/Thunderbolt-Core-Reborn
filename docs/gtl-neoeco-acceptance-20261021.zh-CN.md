# Forge 1.20.1 GTL：NeoECO 验收记录（2026-10-21）

## 结论

**部分通过。** `30df54c7d9720bb137604681e0f80b0e82ef811b` 的生产代码通过真实旧版 NeoECO 服务端验收（3/3）及默认 Forge 47.1.3 发行包检查。新版原生精确批次和取消回收接口仍只有协议测试证据，尚不能认定完整机器验收通过。

本次新增可复现的独立 GameTest 源集、只观察调用的 Mixin 和运行脚本，未修改生产实现。上轮同一生产代码的完整回归为 **1592 通过、4 跳过、0 失败**，兼容与存储 GameTest 为 **9/9**；这些是[前次结果](gtl-forge-eco-port-20261021.zh-CN.md)，本次未重复运行或累加计数。

## 真实运行验证

| 项目 | 本次环境或结果 |
| --- | --- |
| Minecraft / Java / AE2 | 1.20.1 / 17 / 15.4.10 |
| 真实 NeoECO | 20.5.0，已有 Mojang 映射开发 JAR |
| NeoECO 开发 JAR SHA-256 | `a413167fb8041c1e3387ddce08884d7da141df759f391aec41695ea573978ee5` |
| 加载依赖 | Registrate、LDLib 1.0.33.b |
| 本次真实 NeoECO 测试加载器 | Forge 47.4.20；该 NeoECO 要求 Forge ≥47.4.0 |
| 发行构建目标 | 保持 Forge 47.1.3，并在真实测试之后恢复默认配置重新构建 |
| 真实服务端 GameTest | **3 个必需测试通过，无跳过** |

测试执行真实 NeoECO 方块实体的方法，没有在运行路径中补入 NeoECO 夹具类，也没有修改真实机器的返回值。测试期间使用 `thunderbolt.gtlCompat=never`，使本项目的 NeoECO 适配器实际注册；没有加载完整 GTL 整合包。

1. 验证真实模组和依赖加载，以及 `thunderbolt:neoeco` 原生适配器在 common setup 注册。
2. 放置真实 `neoecoae:crafting_pattern_bus`，使用 AE2 从原版铁块拆解配方编码、解码的真实合成样板。未成形总线报告忙碌，准备容量为 0，批次派发全部拒收且不调用原生入口。另直接调用兼容层，观察真实三参数 `pushPattern` 接收到指定 UUID 恰好一次、返回拒收，输入铁块数量保持不变。观察 Mixin 不取消或替换机器行为。
3. 明确断言旧制品没有 `ECOExactBatchProvider`；精确准备安全返回无能力，缺少新取消入口时调用取消通知不抛异常。

首轮测试为 2 通过、1 失败：验收用例错误假设未成形总线能准备一次普通派发。检查真实字节码确认 `isBusy()` 在缺少控制器时为真后，将断言修正为容量 0，并加入独立 UUID 入口验证；第二轮 3/3 通过。生产实现未为此修改。

**本次未验证已成形多方块的成功接单、加工产物交付或取消后的库存回收。** 准备时捕获 UUID 的可变借用视图场景沿用上轮隔离协议测试证据；本次真实测试验证的是直接调用时 UUID 的实际传递。

## 新接口制品检查

参考 [NeoECO 的 Forge 1.20.1 源码](https://github.com/DancingSnow0517/NeoECOAEExtension/tree/a6b3d776d677c3093954081d40d4871592864c3b)。该提交包含原生精确准备和 `cancelAndRecover`，但最近三次官方构建均失败；最近成功构建来自较旧的 `aa0dba02c54e3aa0caf044a33f2e2fba59a86221`。21.2.9 发布版属于 NeoForge 1.21，不能代替本平台制品。

本次从独立副本尝试构建新版源码：离线配置阶段缺少 `io.freefair.lombok:8.7.1` 插件；在线解析长时间无进展后主动终止。未生成可用于新版机器验收的完整制品。源码归档 SHA-256 为 `e3f2ec0ff523f8330868f29f502c72103649f42a3267ee6b4fc0aefcd5ba5ee3`。

因此，上轮 12 项新原生协议测试通过仍仅代表调用契约成立。**新版真实机器的 BigInteger 精确接单及取消回收验收待完成**；本项目的 `prepareExact` / `cancelJob` 是供独立 CPU 使用的公共交接能力，这些接口本身不代表已经接入完整机器生命周期。

## 发行包

真实测试结束后，不带验收 init 脚本执行默认 `verifyReleaseJar`，结果通过。

`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5,423,787 字节。

```text
SHA-256: fd76295700710aa6c5fea025d451b25a5c0939b39192e72da100b6fb22502852
```

逐项解压比较与候选包 `b0a0273e2f981308f6f90c4982bd968dc9ef6a1e6045052b1ed1e1f2b9174680` 的全部文件内容相同；545 项 ZIP 时间戳变化使整包哈希改变。534 个主类均为 Java 17 字节码，内嵌依赖未超过 Java 17，旧结果构造器及精确字节成本接口保留。生产 Mixin 配置和生成 refmap 一致，不含 GameTest、`neoecoacceptance` 观察类或 NeoECO 夹具类。

## 复现

先准备匹配的 Mojang 映射 NeoECO 20.5.0 与 Registrate 开发 JAR，以及 LDLib 1.0.33.b。占位路径需替换为实际文件或独立目录；本地 Maven 参数仅在需要补充 LDLib 依赖仓库时使用。

```powershell
.\gradlew.bat runGameTestServer -Pforge_version=47.4.20 `
  "-PneoEcoAcceptanceJar=<NeoECO 开发 JAR>" `
  "-PneoEcoAcceptanceRegistrate=<Registrate 开发 JAR>" `
  "-PneoEcoAcceptanceMaven=<可选本地 Maven 仓库>" `
  "-PneoEcoAcceptanceTemp=<临时目录>" `
  "-PthunderboltDevGameDirectory=<独立验收目录>" `
  -I tools/neoeco-acceptance-game-tests.gradle --offline `
  --no-parallel --max-workers=1 --no-build-cache --console=plain

.\gradlew.bat verifyReleaseJar --offline --no-parallel --max-workers=1 --no-build-cache
```

init 脚本只启用独立验收源集，并在服务端任务结束后恢复生产 Mixin 资源。验证器要求日志明确出现 3 个必需测试全部通过。验收和发行打包分别执行。

本次不覆盖完整 GTL 整合包、客户端 GUI、多人环境及历史独立 Java 17 超时问题。详见[结构化结果](benchmarks/gtl-neoeco-acceptance-20261021.json)。原始日志、首轮失败证据、世界及外部源码副本保留在本地忽略目录与本轮专属临时目录，不提交开发环境日志。
