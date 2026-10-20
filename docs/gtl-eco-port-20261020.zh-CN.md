# GTL 1.20.1 ECO 订单归属与精确交接移植（2026-10-20）

将上游 Yang120231 的 [9cb676b](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/9cb676b9a3d78a9cb4539c3f9603cb227b9bb6c7) 移植到本地 `fdd766c` 基线。目标保持 Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10 / Java 17，模组版本保持 2.0.0。保留原项目 LGPL 许可证与署名。

## 行为变化

- allocated FastPath 不支持或明确拒收时，单份回退通过可选 `pushPatternForJob` 保留当前订单 UUID。供应器不提供新能力时仍直接使用普通 `pushPattern`。
- 本地独有的 Forge 批量协议桥也使用上述入口。准备普通单份任务时复制材料并捕获当时的 UUID，不保留借用的实时 `BatchJobView`；后续单份派发使用当次订单标识。
- 增加可选 `prepareExact` / `ExactBatch` 适配，保持 `BigInteger` 数量，要求准备数量大于零且不超过请求数量；准备与提交分开，拒收与异常保持不同语义。
- 增加可选 `cancelJob` 通知入口。接口不存在时直接返回，通知失败记录日志。
- 继续优先选择 Forge 原生协议桥，并保留 allocated 提交状态不确定时的原有不退款保护。普通提交异常传播原异常，不进行第二次提交。

精确接口向独立 CPU 提供交接能力，材料提取、能量、等待产物及接收量结算继续由调用方负责。Thunderbolt 没有在本次改动中自动替换其他模组的 CPU 生命周期或结算逻辑。旧版 ECO 可以继续工作，但不会因安装 Thunderbolt 自动获得 ECO 自身缺少的接口。

## 验证结果

以下结果均在本次修改后实际执行。所有原有断言和测试限时保持原样。

| 验证 | 通过 | 跳过 | 失败 |
| --- | ---: | ---: | ---: |
| ECO 与批量调度专项 | 78 | 12 | 0 |
| 真实 NeoECO 20.5.0 旧协议专项 | 9 | 3 | 0 |
| 完整 `test` | 1515 | 4 | 0 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 |
| `cpSatColdRefinementTest` | 1 | 0 | 0 |
| `materialDagColdReplayTest` | 20 | 0 | 0 |
| 完整回归合计 | **1575** | **4** | **0** |
| Forge 实服兼容与存储 GameTest | 9 | 0 | 0 |

专项与完整回归覆盖有重叠，不应相加计为独立测试总数。完整 `check verifyReleaseJar` 的 5 个测试任务均实际执行，未复用测试缓存；编译使用增量结果。GameTest 后再次执行发行包检查，确认已恢复生产 Mixin 配置。

### 新能力与真实旧协议分别验证

新增 14 项隔离协议测试全部通过，覆盖订单标识、借用输入隔离、明确拒收、未知接受状态、异常和 Error 不重试、超过 long 的精确数量、非法接收数量、准备/提交分离、取消失败，以及旧接口缺少新方法时的回退。协议夹具在 Java 17 下动态编译，用独立类加载器加载；它只验证接口交互，不模拟 NeoECO 机器执行，也不会进入发行包。

真实旧协议验证使用本机已有的 Mojang 映射 NeoECO **20.5.0 开发 JAR**，不是新接口的完整运行制品。其容量、任务上下文、输入所有权、拒收和异常测试 9 项通过。该原子布尔协议没有回执／并行接口，相关 3 项按原测试条件跳过。完整套件的另 1 项跳过为缺少 SuperMatrix 实际运行协议。

首次真实协议测试的启动参数属性大小写不正确，导致 12 项全部跳过；修正为 `neoecoProtocolPath` 后重新运行，得到上述 9 通过、3 跳过结果。两次原始记录分别保留。

完整回归可使用仓库原有 `check verifyReleaseJar` 任务，并通过 `-PneoecoProtocolPath=<映射后的开发 JAR>` 启用真实旧协议测试。新协议夹具测试不依赖额外 ECO 制品。

## 发行包

`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5,419,343 字节。

```text
SHA-256: 3ce9516198a051540ac76ca08337ea0aae28e73b069003c49b1df6e161ff9458
```

主包 532 个类均为 Java 17 字节码，内嵌依赖未超过 Java 17。Mixin 配置与生产源码一致，refmap 与编译生成物一致；未包含 NeoECO 协议夹具、测试类或 GameTest 注入。

## 验证边界

新 ECO 接口已通过隔离协议测试，尚未与提供这些接口的真实 Forge ECO 制品完成游戏内整合验收。20.3.0 结构检查制品和上述 20.5.0 开发制品都不包含新 facade；没有提高 Forge 最低版本或捆绑 ECO。

本次没有修改规划器算法或限时。完整回归中的限时用例通过，但此前独立 Java 17 复测记录的 5 次超时仍属于既有证据，不能据此宣称已修复。未进行完整 GTL 整合包、客户端 GUI 或多人验收。

详见[结构化结果](benchmarks/gtl-eco-port-20261020.json)。原始日志、测试 XML、临时编译产物和世界数据保存在本地忽略目录及本轮专属临时目录，不加入提交。
