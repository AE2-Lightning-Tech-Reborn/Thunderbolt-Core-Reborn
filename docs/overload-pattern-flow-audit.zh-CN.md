# 过载样板同 ID 接续检查

检查版本：Minecraft 1.21.1，Thunderbolt Reborn `alpha` 基线 `28f5f8f`，
AE2LT Reborn `alpha` 基线 `e49b9900`，AE2 依赖文件 `7027323`。

## 流程与归属

| 阶段 | 实现 | 检查结论 |
| --- | --- | --- |
| 编辑、转换及保存 | LT `OverloadPatternEncoderMenu`、`PatternConversionService`、`OverloadPatternPayloadTagCodec` | 按源样板槽号保存 STRICT / ID_ONLY，保留原样板及具体组件 |
| 解码及匹配 | LT `OverloadPatternDecoder`、`Ae2OverloadPatternDetails` | 输入候选保留具体 key，`isValid` 实现忽略组件；输出保留目录 key，另声明晚绑定语义 |
| 网络登记 | AE2 `NetworkCraftingProviders` | 实际依赖只登记主产物，副产物不出现在 `getCraftables` / `getCraftingFor` |
| 计算 | TB `FastCraftingPlanner`、`IdOnlyInputGroups` | 同 ID 输入共享物理库存；未知组件的产物只能供给声明完整同 ID 接受范围的输入 |
| 提取与下发 | TB `ParallelBatchCpuHelper`、`BatchExecutor`；LT `ExecutionInputAllocator` | 保留真实样板身份；执行端选实际 key，批量输入和未接受数量按实际提取结算 |
| 输出及恢复 | LT `OverloadCpuStateManager`、`Ae2LtCraftingIntegration`、CPU mixin | 按作业和输出槽登记预期产物，用实际返回 key 结算；恢复后仍保留原始预期 key，结束时清理 |

## 修复的计算问题

1. **已可达配方的副产物未加入同 ID 候选。** 例如上游标记 `item{A}`，
   下游输入标记 `item{B}`，双方忽略 NBT；如果该物品是副产物，原先仅扫描
   AE2 主产物目录会漏掉它。现在把当前链条发现的副产物加入计算内索引。
   若此前已检查过同一物品族，则在同一工作预算内重新建图，消除发现顺序影响。
2. **纯 ID_ONLY 输入未连接晚绑定副产物。** 原先只有 STRICT / ID_ONLY 混合需求
   才补充晚绑定连接，但副产物始终存放在独立的晚绑定资源池。现在对已发现的
   晚绑定副产物直接补齐连接。STRICT 仍不能把未知组件产物当成精确材料。
3. **超出 long 的预览漏用副产物余量。** 精确诊断选择替代路线时只看独立生产能力，
   没看已选择配方产生的余量，可能同时显示副产物正在合成和同 ID 材料缺失。
   现在选路计入现有余量，保留完整 BigInteger 数量。

索引只补充本次已可达配方的副产物，不遍历所有无关配方，也不把逻辑资源复制成物理库存。
物理 stock、样板任务和输出预期仍保留具体 key；逻辑分组不会写入 CPU 存档或网络报告。

## 验证

- TB `./gradlew build`：1,383 项测试，5 项跳过，零失败。
- 新增 16 个适配层回归用例：输出/输入模式矩阵、不同组件、发现顺序、
  STRICT 混合输入、共享库存防重复消费、V2 / CP-SAT 和 BigInteger 预览。
- 新增 1 个精确诊断回归：只由副产物供给的替代输入在超大数量下不会误选缺失模板。
- LT 相关测试 75 项全部通过，包含新增的 4 个 `OverloadPatternPipelineTest` 用例：
  使用真实 `AEItemKey`、LT 过载包装和 AE2 原生目录，覆盖 payload 往返、计算、
  批量提取、返回第三种组件变体、预期量清零，以及存档恢复后继续结算。
- LT 的联动测试可通过 `scripts/overload-flow-test.init.gradle` 与
  `-Pae2ltThunderboltTestJar=/path/to/thunderbolt.jar` 指定候选 TB 构建，避免误测旧依赖。

以上包含源码审查、真实库对象的 JUnit 联动和构建验证；没有在游戏世界中操作编码器 GUI、
具体加工机器或完整整合包，因此不能把这些结果描述为所有第三方机器的实机验收。
