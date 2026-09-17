# 非自增环直接求解的边界

## 当前代码限制的实际含义

`ConservativeFeedbackAnalysis` 对保留下来的反馈物料图区分两类：严格 marked cycle 使用整数执行比例与启动前缀；更复杂的普通 SCC 要找到正权重 `w`，使每条内部转换的 `w·Δ <= 0`，才能进入保守回放。这限制了内部循环的加权增长，并不证明所有满足收支方程的配方次数都能执行。

原始候选图中的其他环仍由 `buildDag/frameFor` 删除回边配方。`CycleAnalysis.LOSSY_CONVERSION` 的方向重试甚至包含有增益的转换比例，因为每个尝试只保留无环子集，不会执行完整增益环。因此不能把当前所有“能做切环重试”的环，都解释为原始完整 SCC 已通过非自增证明。

## 两个直接运行的例子

### 完全守恒仍可能需要同时执行两个方向

```
P1: 3A -> 9B
P2: 3B -> A
P3: 2A + 3B -> T
库存 A=3，请求 T=1
```

内部转换按权重 `A=3,B=1` 完全守恒。需要执行 `P1` 一次、`P2` 两次，再执行 `P3`，真实库存变化为 `(3,0) -> (0,9) -> (2,3) -> T`。

当前切环路径报告缺 `B=3`；在仓库现有整数求解器中保留完整原始配方，模型直接得到执行次数 `[1,2,1]`，独立逐批执行通过。这说明完整环进入整数模型有实际收益，继续增加切环方向数不能覆盖所有这种用法。

### 非自增不保证可启动

```
P1: 2A -> B + C
P2: 2B -> C + A
P3: 2C -> A + B
库存 A=1,B=1,C=0，请求 C=1
```

每条配方都保持 `A+B+C`，权重 `(1,1,1)` 已是严格守恒证明。收支模型给出 `[1,1,0]`，最终余额 `(0,0,2)`，但初始状态没有任何配方能执行，实际死锁。当前执行检查正确拒绝这个结果，`NonGrowingCycleReachabilityTest` 固定此安全边界。

## 找到的专用工具与可借鉴方法

- [LEMON NetworkSimplex](https://lemon.cs.elte.hu/pub/doc/1.2.3/a00231.html) 和 [OR-Tools 最小费用流](https://developers.google.com/optimization/flow/mincostflow) 是网络流求解器。它们的原始模型围绕边流量和节点守恒；我们的 `A+B -> C` 要求多种输入按固定比例同时参与一次整数执行，不能直接用普通流量分流代替。给流模型附加这些批次耦合约束后，也不能继续假定它具有普通最小费用流的性质。
- [TINA](https://projects.laas.fr/tina/) 是 Petri 网分析工具集，包含可达状态、结构分析和保持状态空间语义的约简；更接近配方消耗/产出以及启动顺序问题。
- [LoLA](https://theo.informatik.uni-rostock.de/en/theo-forschung/tools/lola/) 专门检查 Petri 网可达性、死锁等性质。
- [SMPT](https://github.com/nicolasAmat/SMPT) 基于 SMT、状态方程和网络约简分析 Petri 网可达性，当前工具以 Python/Z3 等外部组件组合运行。
- [Applying CEGAR to the Petri Net State Equation](https://arxiv.org/abs/1208.2159) 给出“状态方程候选 + 反例驱动细化”的路线；[Checking marking reachability with the state equation in Petri net subclasses](https://arxiv.org/abs/2006.05600) 研究哪些额外结构与行为条件能让状态方程足以判断可达性。不能只从“有非自增权重”推导出这些更强条件。

这些资料已核查，但本轮没有安装或嵌入上述外部工具，也没有把通用环余额模型接入生产可行性出口。实例原型直接使用已有 `BoundedIntegerLinearSolver`，没有新增依赖。

## 建议的底层方向

在删除回边之前，对原始完整 SCC 做非自增与语义准入。为可准入 SCC 及其共享资源耦合区域保留全部配方，直接求整数执行次数；不能把不同 SCC 的共享库存分别当作全部可用。严格加权环继续用闭式执行块，其他环必须验证实际启动和顺序。

余额候选无法执行时，利用可证明的缺失启动条件或结构约束细化模型，再求下一个候选。仅仅有界回放未成功，不能作为排除一个执行向量的数学证明。预算不足时保留现有可执行方案或已验证的补料方案。

这个方向消除的是“先删掉有效配方，再反复更换切法”的建模损失，而非取消预算。普通 DAG 仍走线性传播；环内局部模型和顺序搜索继续受同一次 calculation 的共享预算约束。
