# Forge 1.20.1 上游选择性移植记录（2026-10-16）

本轮将上游 Forge `1.20.1` 的规划、批量派发、精确订单和 NeoECO 兼容改动选择性移植到 `1.20.1_GTL`。

- 来源：[`407cb75`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/407cb75)、[`43730db`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/43730db)、[`94148db`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/94148db)、[`c41f1f7`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/c41f1f7) 与 [`c3725a7`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/c3725a7)。
- GTL 基线：`bf650678a2c495a0645011392801236e3d3e6a1f`，保留该分支已有本地实现。
- 原项目许可证和必要署名继续适用。

## 移植范围

1. **规划预算与认证。** 可行方案优化各阶段共享总预算及停滞截止；候选返回后检查截止并保留最近认证方案。大 CP-SAT 模型在联合图可证明无环时固定排序，并为原生求解与独立认证预留共享截止时间。
2. **批量准入与跨 tick 派发。** 加入准备容量、普通单份回退、拒收回滚、二次计费重载和跨 tick 调度状态恢复；保留 GTL CPU 的材料所有权与交还逻辑。
3. **大数量订单派发。** 通过有界 long 切片把精确大数量订单分批交给旧式 long provider，分别限制物理输入库存和输出数量，并保持共享材料只供给与返还一次。精确计划报告显式传输是否仅供预览。
4. **NeoECO Forge 20.5 批量协议。** 反射识别单份容量上下文、普通并行输入协议、布尔接收与部分接收回执；只在线性配方上批量派发，动态并行注册不跨 tick 缓存。提交结果不确定或提交阶段异常时不退款重试。

保留 GTL 的 CPU 交还逻辑、JNI 初始化校验、原有库存下界判定和已有规划快速路径。本轮没有移植来源提交中的 NBT 功能及无关重构。

## 移植中修正的问题

- 同步 `FeasibleCostPropagationTest` 的共享预算构造参数，保留传播和取消断言。
- 保留 CP-SAT 外层完整候选截止，只将原生求解切片缩至 75%，为独立执行认证留出时间，避免超时丢弃已认证方案。
- 将长图性能回归放入独立 JVM，避免其 3 秒断言受前序原生求解和 JIT 历史影响；测试图规模与断言不变。

## 验证

环境目标为 Minecraft 1.20.1、Forge 47.1.3、AE2 15.4.10 和项目 Java 17 工具链。执行完整 `check verifyReleaseJar --rerun-tasks` 后，Gradle 检查与发行包验证均通过。

| 验证 | 结果 |
| --- | --- |
| JUnit 与隔离回归任务 | 1,467 通过，0 失败，13 跳过 |
| `test` | 1,407 通过，0 失败，13 跳过 |
| 规划边界与冷启动回归 | 38 边界、1 CP-SAT 冷启动、20 DAG 回放、1 大图回归均通过 |
| `verifyReleaseJar` | 通过，包含重映射 JarJar 与发行 refmap 校验 |
| `git diff --check` | 通过 |

13 项跳过中，12 项 NeoECO 协议测试需要通过 `neoecoProtocolPath` 提供实际 20.5 JAR；本机缓存只有 20.3、20.4.1 和 20.4.2。另有 1 项 EAEP 实际协议测试缺少可选 JAR。专服 `runGameTestServer` 成功启动，但此工作树未注册 GameTest，运行结果为 0 项，因此不计作运行时覆盖。

2026-10-17 更正汇总计数：只统计该次执行的 `test`、`externalPlannerBoundaryTest`、`cpSatColdRefinementTest`、`materialDagColdReplayTest` 和 `materialDagLargeGraphTest` 五个任务，排除旧审计与重复边界任务遗留的 XML；各任务结果未变。

ForgeGradle 的 HTTPS 连通性预检在当前环境的 Java TLS 握手处停滞；重跑时使用 `--system-prop net.minecraftforge.gradle.check.certs=false` 跳过该预检，Gradle 依赖解析、完整测试和发行包检查随后完成。原始运行输出保留在仓库外，没有纳入提交。
