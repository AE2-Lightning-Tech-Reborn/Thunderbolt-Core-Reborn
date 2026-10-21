# GTL 1.20.1 原生 Forge NeoECO 接口移植（2026-10-21）

将上游 small-mumu 的 [ee38e69](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/ee38e6997d8e772862424b1c26b997a8ddd3e5f3) 和 [cea4cc2](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/cea4cc29b97e308987ad4a06a2c61f7fb00f0527) 适配到 `656a1c9` 基线，补齐原生 Forge 接口，同时保留既有 facade 支持。目标保持 Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10 / Java 17，模组版本保持 2.0.0。保留原项目许可证与署名。

## 行为变化

- 原生样板总线提供带 UUID 的 `pushPattern` 重载时，普通回退使用该入口，保留订单归属。其他供应器继续使用已有 facade 或普通接口。
- 优先使用原生 `ECOExactBatchProvider`，保留 `BigInteger` 数量，为其构造拥有独立材料副本的逐份上下文，并将返回的 `BooleanSupplier` 封装为延后执行的提交操作。接口缺失时仍可使用已有精确 facade。
- 拒绝非正请求、非法接收数量及缺失的提交回调。原生接口已被选中后，其拒收、空上下文或异常不会触发另一套准备或派发入口。
- 优先向 `ECOCraftingJobLifecycle.cancelAndRecover` 通知取消；该方法缺失时仍尝试 facade。通知失败记录日志，不追加第二次取消调用。
- 保留本地在准备阶段捕获 UUID、隔离借用输入，以及不确定接受状态下禁止重试的处理。提取材料、能量与结算仍由调用方负责。

上游 `1.20.1` 此次新增的无限盘优化与大数字节报告在本地已有对应实现，本轮集中补齐原生 NeoECO 接口，未改规划算法或存储实现。

## 验证结果

| 验证 | 通过 | 跳过 | 失败 |
| --- | ---: | ---: | ---: |
| NeoECO 专项（含真实旧协议） | 40 | 3 | 0 |
| 新增原生 Forge 隔离协议测试 | 12 | 0 | 0 |
| 完整 `test` | 1532 | 4 | 0 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 |
| `cpSatColdRefinementTest` | 1 | 0 | 0 |
| `materialDagColdReplayTest` | 20 | 0 | 0 |
| 完整回归合计 | **1592** | **4** | **0** |
| Forge 服务端兼容与存储 GameTest | 9 | 0 | 0 |

新增测试包含在专项与完整回归中，不重复累加。5 个测试任务均通过本地 init 强制实际执行，未复用测试缓存；断言和限时保持原样。GameTest 后恢复生产 Mixin 配置，并再次执行 `verifyReleaseJar`。

12 项新协议测试通过 Java 17 动态编译隔离夹具，覆盖原生接口独立存在、两套接口共存、接口缺失、UUID、借用输入、超过 long 的接收量、非法回执、延后提交、拒收、异常和 Error 不重试，以及取消通知失败。这些夹具只验证调用契约，不执行 NeoECO 机器逻辑。

真实旧协议使用已有 Mojang 映射 NeoECO 20.5.0 开发 JAR，9 项通过、3 项因缺少回执／并行接口跳过。检查其字节码签名确认普通派发的 UUID 重载存在；该制品虽有生命周期类，但没有 `cancelAndRecover`，也没有 `ECOExactBatchProvider`。因此新精确准备与取消接口的验证依据是隔离夹具。完整套件另有 1 项因缺少 SuperMatrix 真实运行协议而跳过。

## 发行包与范围

`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5,423,787 字节。

```text
SHA-256: b0a0273e2f981308f6f90c4982bd968dc9ef6a1e6045052b1ed1e1f2b9174680
```

534 个主包类均为 Java 17 字节码，内嵌依赖未超过 Java 17。生产 Mixin 配置和 refmap 匹配，旧结果构造器仍存在，发行包不包含 NeoECO 夹具源码、测试类或 GameTest 注入。

未完成提供新接口的真实 NeoECO 制品游戏内验收，也未进行完整 GTL 整合包、客户端 GUI 或多人验收。历史独立 Java 17 超时证据按[前次报告](gtl-eco-port-20261020.zh-CN.md)保留，本次通过不代表该问题已修复。

详见[结构化结果](benchmarks/gtl-forge-eco-port-20261021.json)。原始日志、测试 XML、临时产物和世界数据保存在本地忽略目录及本轮专属临时目录，不加入提交。
