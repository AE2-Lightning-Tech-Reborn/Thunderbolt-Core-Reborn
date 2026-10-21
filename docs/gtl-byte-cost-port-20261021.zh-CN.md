# GTL 1.20.1 大数合成报告字节成本移植（2026-10-21）

将上游 Reborn Maintainers 的 [afefa96](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/afefa969c5e06901aea5c47d5481999278578012) 移植到 `8ad2d57` 基线。目标环境保持 Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10 / Java 17，模组版本保持 2.0.0。保留原项目许可证与署名。

## 行为变化

- `BigCraftingPlanner.Result` 保留精确诊断计划，报告可读取执行托管材料之外的中间加工数量。成功、缺料及未通过执行证明的返回分支均携带诊断计划。
- 保留原三参数构造器及原有可执行条件；通过旧构造器创建的结果，其 `diagnostic()` 为 `null`。
- 将预览已有的字节成本算法提取为公共 `ExactCraftPlan.byteCost(...)`，使用 `BigInteger` 保留超过 `long` 的成本。不同单位的分数先相加，再统一向上取整，并计入加工次数、返还输入、容器余料和耐久工具成本。
- 预览改为调用公共方法，保留本地库存与可发射材料的精确拆分逻辑。未改变规划器求解算法、执行适配器或测试限时。

## 本次验证

| 验证 | 通过 | 跳过 | 失败 |
| --- | ---: | ---: | ---: |
| 精确规划、字节成本与大数执行专项 | 27 | 0 | 0 |
| 完整 `test` | 1520 | 4 | 0 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 |
| `cpSatColdRefinementTest` | 1 | 0 | 0 |
| `materialDagColdReplayTest` | 20 | 0 | 0 |
| 完整回归合计 | **1580** | **4** | **0** |
| Forge 服务端兼容与存储 GameTest | 9 | 0 | 0 |

专项与完整回归有重叠，不相加作为独立测试总数。`check verifyReleaseJar` 的 5 个测试任务均通过本轮本地 init 强制实际执行，未复用测试缓存；编译使用增量结果。新增上游 4 项测试覆盖 `long` 边界、百位以上数量、深层配方链、混合单位取整及返还成本；另补 1 项旧构造器契约测试。

完整套件使用已有的 Mojang 映射 NeoECO 20.5.0 开发制品验证旧协议。4 项跳过分别为该制品缺少的 3 项回执／并行能力，以及 1 项缺少 SuperMatrix 真实运行协议的测试。原断言及限时均未调整。

GameTest 完成后恢复生产资源，再次执行 `verifyReleaseJar`。发行包中旧构造器的 JVM 描述符及新的公共字节成本方法均存在；532 个主包类全部为 Java 17 字节码，内嵌依赖未超过 Java 17。Mixin 配置与生产源码一致，refmap 与编译生成物一致，未包含测试类、GameTest 或可选协议夹具。

## 发行包与范围

`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5,420,294 字节。

```text
SHA-256: 38d0ea7dc9d9781e97d52ee78b4c5fc4abb651a28328522330b49ff8bb3eb462
```

本次验证覆盖 Forge 服务端及上述自动化测试，未进行完整 GTL 整合包、客户端 GUI 或多人验收。既有独立 Java 17 复测的超时证据按[前次报告](gtl-eco-port-20261020.zh-CN.md)保留，本次通过不代表该历史问题已修复。

详见[结构化结果](benchmarks/gtl-byte-cost-port-20261021.json)。原始日志、测试 XML、临时编译产物及世界数据仅保留在本地忽略目录和本轮专属临时目录，不加入提交。
