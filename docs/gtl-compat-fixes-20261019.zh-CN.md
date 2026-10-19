# GTL 长数量与确认摘要兼容修复（2026-10-19）

对应 [移植后兼容检查](gtl-compat-audit-20261019.zh-CN.md) 中的两类适配缺口和 AE2LT 大数摘要风险。

## 最终行为

- GTLCore 的 `gtlcore$planLongAmountJob` 与普通算料入口一样，在启动新任务前清理旧算法状态，再通过可组合的 `WrapOperation` 跟踪新 future。长数量确认、重算和库存预留冲突后重算都能关联到实际选中的算法。
- Thunderbolt 自建摘要通过可选的 GTL 行方法填充合成次数。计数包含所有输出、忽略非正执行次数，并在重复输出汇总时饱和到 `Long.MAX_VALUE`；计数不乘产物堆叠量。未安装 GTL 时无需加载其类。
- 精确数量显示只替换 AE2 添加的三种数量行，让原生方法继续执行。GTL 的合成次数、库存占比和库存占用提示可继续追加，超长数字仍分行保留全部位数。
- Crafting Tree 的兼容适配不再在 `fromJob` 的 HEAD 改写计划类型。它只在原生摘要的构造调用前为尾部的 Crafting Tree 回调提供具体 AE2 计划；第三方 HEAD 摘要工厂可先识别原始类型。
- `WrapMethod` 在摘要工厂返回后补齐 Crafting Tree 载荷，包括第三方提前返回的摘要。已有载荷保留，不替换其他工厂生成的树。该 Mixin 只在 `ae2ct` 存在时启用。

这里没有改动 GTL CPU 派发避让规则、规划算法预算或精确预览的提交限制。

## 回归验证方式

普通自动检查：

```powershell
.\gradlew.bat check verifyReleaseJar --rerun-tasks --offline --max-workers=1 `
  --system-prop net.minecraftforge.gradle.check.certs=false --console=plain
```

隔离的 Forge 组合验证：

```powershell
.\gradlew.bat -I tools/gtl-compatibility-game-tests.gradle runGameTestServer `
  --offline --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false --console=plain
```

组合测试源位于 `src/compatibilityGameTest`，只由上述初始化脚本启用。普通构建不编译或打包这些夹具。脚本在运行结束后恢复生成的 Mixin 配置，并拒绝与发布打包任务混用。

组合环境为 Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10 / Java 17，加载实际 Crafting Tree 1.1.1 JAR。GTL 的长数量入口、行元数据和 AE2LT 的专属摘要 HEAD 工厂使用最小接口夹具，测试实际 Mixin 变换后的目标类：

1. 长数量超过 `Integer.MAX_VALUE` 时不截断；旧状态被移除，新 future 被跟踪，并保留原入口调用。
2. 第三方 HEAD 工厂返回的摘要对象和 151 位精确数量保持不变，并补齐 Crafting Tree 载荷。
3. 普通循环计划仍能通过原生摘要路径和 Crafting Tree 的具体类型转换。
4. 自建摘要在应用了 GTL 形状的行扩展后，含正确的执行次数和 Crafting Tree 载荷。

全局 `MixinEnvironment.audit()` 在此隔离专服中关闭，以免强制加载 Crafting Tree 配置中的客户端目标；测试自身会实际加载并执行上述公共目标类。

## 本轮验证结果

`check verifyReleaseJar --rerun-tasks` 成功，17 个任务全部实际执行。按本轮五个测试任务的 XML 报告统计，不重复计入此前定向运行：

| 任务 | 通过 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| `test` | 1458 | 0 | 13 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 |
| `cpSatColdRefinementTest` | 1 | 0 | 0 |
| `materialDagColdReplayTest` | 20 | 0 | 0 |
| 合计 | 1518 | 0 | 13 |

13 项跳过与检查基线一致：12 项需要另行提供 NeoECO 新协议产物，1 项需要带 SuperMatrix API 的产物。隔离 Forge GameTest 另有 4 项必需测试全部通过，不计入上表。

发布包：`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5,401,437 字节。

SHA-256：`f85255495c541b4c8ebbcf5425c359483930ff7a84e3174d0305c9572ff92a0e`。

`verifyReleaseJar` 检查通过。额外检查确认发布包包含新增的 GTL 摘要桥接类，不含组合测试类、夹具 Mixin 或测试结构；打包的 Mixin 配置与正常源码配置逐字节一致。主包 523 个类全部为 Java 17 字节码（major 61），递归检查的内嵌类不超过 major 61。

## 验证边界

这组测试验证修复涉及的公共接口与 Mixin 组合，不等于加载了完整 GTLCore、AE2LT 整合包。完整整合包的客户端视觉效果和端到端合成仍需实机验证。客户端数量行的调用锚点由 AE2 实际二进制形状测试校验，格式化与附加提示保留由 JUnit 验证。
