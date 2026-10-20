# GTL 1.20.1 修复复验（2026-10-20）

本次验证通过。环境为 Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10 / Java 17.0.20，验证对象为 `a305f6f` 基线上的容量记账、弹出注册持久化及 CP-SAT 见证计时修复。验证期间功能代码、测试代码、兼容夹具和构建配置的内容指纹保持一致，没有修改预算或放宽断言。

## 强制完整检查

```powershell
.\gradlew.bat check verifyReleaseJar --rerun-tasks --no-build-cache --continue `
  --offline --no-parallel --max-workers=1 `
  --system-prop net.minecraftforge.gradle.check.certs=false --console=plain
```

| 任务 | 通过 | 跳过 | 失败 |
| --- | ---: | ---: | ---: |
| `test` | 1465 | 13 | 0 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 |
| `cpSatColdRefinementTest` | 1 | 0 | 0 |
| `materialDagColdReplayTest` | 20 | 0 | 0 |
| 合计 | 1525 | 13 | 0 |

全部 5 个测试任务均实际执行，未复用缓存或 `UP-TO-DATE` 报告。13 项跳过为缺少外部协议制品的 12 项 NeoECO 和 1 项 SuperMatrix 测试，不计为通过。

## 间歇失败用例的独立 JVM 复测

每次启动全新 Java 17 JVM，JUnit 正常执行测试及其初始化，保留原断言与原时间限制；同时校验发现数、通过数、容器失败及跳过情况。

| 用例 | 独立 JVM 次数 | 结果 |
| --- | ---: | --- |
| CP-SAT 冷启动死锁替代路径 | 10 | 10/10；每次包含 8 轮新图，共 80 轮 |
| 终端恢复的多单位预算拒绝与缺料见证 | 4 | 4/4 |
| 包装配方下竞争路线的旁产物 | 4 | 4/4 |
| 宽度 16 的双输入库存分配 | 4 | 4/4 |
| 补料重规划使用新的截止时间 | 4 | 4/4 |
| 大型旁产物 DAG 的原 3 秒限制 | 4 | 4/4 |
| Java 见证阶段暂停 200 ms 的回归 | 3 | 3/3 |
| 合计 | 33 | 33/33 |

本轮未复现早期失败。[初轮报告](gtl-full-audit-20261019.zh-CN.md) 和其失败证据摘要继续保留；有限次数通过不能证明间歇失败从此不再发生。

## 实际 Forge 回归与发行包

仓库内兼容测试 7/7；实际合成、请求器、库存及生命周期测试在 `auto` 和 `always` 两种模式下各 11/11，合计 29 次执行全部通过。GameTest 结束后重新构建并执行 `verifyReleaseJar`。

最终文件为 `build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5401695 字节，SHA-256：

```text
8567aba7d7f49d977a84b45739298f24de99d66e962b2bd8e1751f6f39467854
```

主包 523 个类均为 Java 17 字节码，内嵌依赖未超过 Java 17；Mixin 配置与普通源码一致，refmap 与生成结果一致。未混入 GameTest、兼容夹具或 `runtime_empty.nbt`。

[结构化复验结果](benchmarks/gtl-reverification-20261020.json) 保存本轮各任务、33 次独立 JVM、Forge 回归、源码指纹及产物校验结果。原始日志、测试 XML 和本机运行工具留在本地忽略目录，没有写入报告。

完整 GTLCore/AE2LT 整合包、客户端渲染和整包 TPS 仍未验证；本次结论限定于上述环境与用例。
