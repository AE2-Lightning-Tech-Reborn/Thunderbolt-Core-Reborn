# 全局批量提供器适配

## 调用与注册

`BatchExecutor` 继续负责材料提取、能耗、任务次数、等待产物和未接收材料的返还。
所有不带适配器参数的 `runBatchOnly` 重载使用 TB 全局表；保留的最终重载传 `null`
具有相同行为。显式传入非空适配器时，只使用该适配器，不再查全局表。
两种入口均优先识别原生 `IBatchCraftingProvider`。

LT 时间轮 CPU 已改用不带适配器的完整调度重载。AE2 和 AdvancedAE CPU 原有的短重载
自动使用同一全局表；各 CPU 的次数预算和材料分配策略保持各自实现。

在 Forge common setup 的串行 `enqueueWork` 中注册：

```java
BatchProviderAdapters.register(
        new ResourceLocation("my_mod", "machine"),
        0,
        (provider, pattern, job) -> {
            // 不匹配返回 null；匹配时返回实现 IBatchCraftingProvider 的端点。
            return adaptMyMachine(provider, pattern, job);
        });
```

也可使用省略优先级的 `register(id, adapter)`，默认优先级为 0。
优先级高者先匹配，同优先级按 ID 字符串升序；内置兼容使用 -100。
重复 ID 抛出异常。`unregister(id)` 可移除注册。`entries()` 是不可变快照。

注册表不持有机器端点、样板或任务。能力只取决于提供器身份时，可实现
`BatchProviderResolver`，按其声明选择 tick 内或跨 tick 缓存。依赖样板或任务的
`BatchProviderAdapter` 每次都获取当前上下文；禁止把借用的 `BatchJobView` 留给后续任务。
缓存仍归执行 CPU 所有，注册变更后的下一次查询会使旧解析结果失效。

## 已接入协议

| 对象 | 路径与边界 |
| --- | --- |
| EAEP | 超级装配矩阵提供器及其子类，使用 `ScaledMolecularAssemblerPattern`。通过全局适配取代给矩阵注入 TB 接口，保留矩阵的配置批量上限。 |
| NeoECO 20.5.1 | 使用 `ECOFastPathFacade.prepareAllocated`，只接受公开接口批准的无返还输入、无状态 NORMAL 配方；不适用时回退单份普通执行。大型工作站已有原生批处理优先。 |
| AppliedE | TB 已有 EMC 模块原生批量 Mixin，直接命中 `IBatchCraftingProvider`，无需二次包装。 |
| LT / LTPP | 已有原生批量接口，继续优先直接调用。 |

EAEP、NeoECO 可选适配由 TB common setup 注册，TB 不依赖 LT 加载。
缺少可选模组或对应 API 时不注册。

2026-10-26 按新的 ECO 兼容要求接入 NeoECO 1.20.1 的公开接口；不恢复旧版机器或 CPU Mixin。
`thunderbolt:neoecoae` 使用反射探测完整接口，不引入强制依赖。GTL CPU 接管激活时不注册可选批处理协议，
由 GTL 与 ECO 原生逻辑处理派发。带日期的旧报告保留各自当时的兼容范围。

适配器在提取前准备，捕获任务 UUID 和输入副本，不持有借用的可变 `BatchJobView`。
CPU 仍独自结算材料、能量与等待产物；ECO 的 allocated 路径使用零能量私有账本。
缩小供给时重新准备；明确拒绝可尝试单份普通派发，提交异常向上传递并停止任务，避免不明所有权下重复投料。
公开 `prepareExact` 辅助接口保留 `BigInteger`，由调用方持有完整事务；普通 Core CPU 不因此自动获得超 long 派发能力。

`pushBatch` 返回**未接收份数**，输入是借用的单份只读模板。
适配器只能向下游交付自己的副本。下游已经进入提交阶段后抛出的异常必须传给执行器，
停止任务，不能伪装成拒收后退款/重试，也不能当成确定成功继续运行。

### Forge 1.20.1 发行版边界（2026-10-04）

- 参考上游 `28a1f25` 移除 Useless 适配器、注册和模拟契约测试。官方
  `26.7.13-Forge1.20.1` 缺少 BigInteger / Smart Doubling 批量 API，继续普通执行。
- EAEP Forge `1.5.5`（`pN9pMjiW`）没有超级矩阵 API，虚拟完成兼容另行保留。
  Forge `1.6.3`（`HNyEHPOp`）已提供缩放样板和超级矩阵，匹配既有桥接协议；
  实际样板类测试不能替代完整游戏内多方块执行验证。
- AppliedE Forge `0.14.3` 的 `TransmutationPattern` 位于 `me.misc`，不是 1.21 的
  `me.service`。原生 EMC 模块 Mixin 按本版本类型识别，额外对发行 JAR 字段/方法锁定回归。

## 验证

TB `test` 覆盖全局顺序、重复 ID、显式覆盖、原生优先、混合缓存策略、跨 CPU 隔离、
同 tick 注册变更、部分接收、原料模板不可变和提交异常。
可通过 `-PthunderboltBatchTestMods=<EAEP JAR 路径>` 加载实际 EAEP 倍率样板契约测试；
多个测试 JAR 使用平台路径分隔符分开。未提供 EAEP 时该实际协议用例跳过。

NeoECO 的隔离协议夹具覆盖准备、部分接收、拒绝、异常和真实 `BatchExecutor` 库存结算；
真实发行包机器测试入口为 `tools/neoeco-compatibility-game-tests.gradle`，环境要求与复现命令见
[2026-10-26 兼容报告](neoeco-compatibility-20261026.zh-CN.md)。测试源码与模板不加入发行包。

LT 提供 `runGlobalBatchGameTestServer -Pae2ltGlobalBatchTestsOnly=true`。
先让 LT 的 Thunderbolt 依赖解析到本次构建，再用
`-Pae2ltJdbProbeMods=<可选模组及其必需依赖 JAR>` 运行发行包接口测试。
普通运行验证真实 Mixin 转换后的批量执行、全局提供器刷新和时间轮材料/产物回流；
加载相应可选模组后还验证 TB 自动注册。
接口用例使用受控提供器，不能替代每一种真实多方块机器的整包长期运行测试。

## 1.20.1-alpha 同步验证（2026-09-28）

同步 Reborn `alpha` 的 `7559206` 全局适配器协议，保留本版本 AE2、加载器及 Java API。
Java 17 / Forge：`test build publishToMavenLocal` 通过。875 项单元测试，0 失败，1 项需要实际 EAEP JAR 的可选测试跳过。
对应 LT 的 `scripts/alpha-sync-test.init.gradle` 还验证了真实 BatchExecutor/Mixin 全局适配分派及材料回流。

以上为当时的同步验证记录；当前可选协议范围以上文表格为准。
上文 LT 的 1.21.1 专用运行任务不直接适用于移植分支；本版本使用 LT 的 alpha-sync 原生测试入口。
