# Core 2.0.4-gto.2：按 GTOCore 文档优化批量派发

参考 GTOCore-Main `0.6.0` 分支提交
[`b244589d2407362cdc4d60f1fb270db61b0c11b4`](https://github.com/GregTech-Odyssey/GTOCore-Main/tree/b244589d2407362cdc4d60f1fb270db61b0c11b4)
的 [编码规范](https://github.com/GregTech-Odyssey/GTOCore-Main/blob/b244589d2407362cdc4d60f1fb270db61b0c11b4/CODING_GUIDELINES.md)
容器、迭代、复制与容量章节。目标环境仍为 Minecraft 1.20.1、Forge 47.4.20、
AE2-GTO 15.2610.31、Java 21。

## 适用要求与实现

- 原始数量与专用键容器：`ParallelBatchCpuHelper` 的固定需求、可变需求、
  可用量、已提取量及共享输出额度改用具体 `AEKeyLongMap<AEKey>`。
  `BatchExecutor` 的输出语义核对也使用同一种容器。
  内部调用原始 `getLong`、`put`、`addTo`，遍历直接使用该类型的快速迭代器，
  消除 `Long` 装箱及普通 Map 逐条目的临时对象。
- 以实际依赖为准：已检查目标 JAR 的 `AEKeyHash`、`AEKeyLongMap` 和
  `KeyCounter` 字节码。该依赖按键对象身份定位，`addTo` 对正数溢出饱和，
  `iterator()` 使用 `fastIterator()`，`KeyCounter.copy()` 克隆底层表。
  没有经宽泛 `Map` 接口调用这些内部操作，也没有保留快速迭代器复用的 Entry。
- 按槽位和具体键数分配容量：常见单键槽从一个键的容量起步，混合槽可正常扩容；
  缩放结果按已解析键数分配。单份普通输入切片直接用 `KeyCounter.copy()`，
  继续给供应器独立可写副本。
- 延迟创建临时结构：普通样板直接按原槽位遍历；显式输入分配仍先解析固定槽，
  只有该路径创建排序数组。返还物列表仅在有返还物时创建，内部拥有的列表直接
  交给结果对象。只有需要追加取料时才创建对应退款表；具体输入路径省去未使用的
  旧式键、单位和共享标记数组。共享输入存在性查询直接使用 `isEmpty()`。

公开取料重载、`BulkResult` 的公开构造和成员、`BatchExecutor` 的公开方法描述符
与上一版本一致，已用 `javap -public -s` 对照。预留库存参数保留既有 `Map` API，
供配套 LT 的调用和分配 Mixin 继续使用。每次派发独立持有材料、退款账本和切片，
部分接收、准备失败和归属不明的提交仍沿用既有结算规则。

数量表同时与 AE2-GTO 的库存键身份保持一致：即使两个自定义键的 `equals()`
返回 true，只要它们是库存中的不同键对象，就分别计算和取回。这修正了原临时
`HashMap` 合并这种键、但实际库存分别存储它们时的数量不一致。

## 本机分配量对照

基线为本地已验证提交 `da9b620ce7c134fba212ea0b896f538f03a845e6` 的编译类，
比较双方使用同一份 `BatchAllocationBenchmark`。每侧两轮独立 JVM；
每个场景预热 20 组、测量 20 组，每组 2,000 次操作。
分配量来自 `ThreadMXBean`，耗时来自 `System.nanoTime()`。
表内为第二轮每次操作分配字节数的中位数。

环境为 Intel Core i5-13400、Windows、Microsoft OpenJDK 21.0.10、最大堆 512 MiB。
一次操作包括取料、生成供应器副本、修改副本验证隔离、完整退款和库存校验。
使用真实依赖的 `ListCraftingInventory`、驻留物品键及预先创建的样板。
普通批次数量为 512，预留库存场景限制为 64；共享场景首槽只取一份种子。

| 场景 | 优化前 B/次 | 优化后 B/次 | 分配减少 |
| --- | ---: | ---: | ---: |
| 原生解析，单槽 | 2,856 | 1,720 | 39.8% |
| 原生解析，9 槽同键 | 19,496 | 9,144 | 53.1% |
| 原生解析，9 槽不同键 | 22,840 | 11,616 | 49.1% |
| 原生解析，9 槽含预留库存 | 22,624 | 11,616 | 48.7% |
| 原生解析，9 槽含共享种子 | 23,088 | 12,104 | 47.6% |
| 旧式直接取料，9 槽同键 | 17,560 | 7,512 | 57.2% |
| 旧式直接取料，9 槽不同键 | 18,528 | 7,392 | 60.1% |

两轮均观察到分配下降。耗时受预热和本机其他任务影响：例如第二轮单槽 p50 从
1.127 µs 变为 1.345 µs，9 槽不同键则从 7.689 µs 变为 5.305 µs。
这里仅证明这些合成场景的分配量减少，不据此推导整合包 TPS 或普遍耗时收益。
原始样本、基线类哈希、对照类哈希和调用日志保留在本地忽略目录
`.gradle/gtocore-guidance/`。

当前版本可重复运行：

```powershell
.\gradlew.bat batchAllocationBenchmark -PbatchAllocationLabel=current --offline --no-daemon
```

输出位于 `build/batch-allocation-benchmark/current.json`。已有基线编译目录时，
增加 `-PbatchAllocationBaselineDir=<classes目录>` 即可用相同测试入口对照旧实现；
JSON 中记录实际载入的两个实现类的 SHA256。
该入口使用项目现有的测试专用 agent 补齐普通 JVM 中缺少的 Forge/AE2 转换，
其结果属于算法和库存协议测试。

## 验证与交付

新增 10 项回归覆盖新旧取料路径的同键多槽、预留量、部分接收、退款幂等、
供应器副本隔离、第二个材料短取后的完整回滚、重试、不同键身份、重复输出的
共享额度，以及输出总量的饱和与不匹配。定向套件 84 项全部通过。

完整检查入口：

```powershell
.\gradlew.bat check build compileGameTestJava '-Dnet.minecraftforge.gradle.check.certs=false' --offline --no-daemon --max-workers=2
python -m unittest discover -s .github/scripts -p test_release.py
```

此处系统属性只在离线命令中跳过 ForgeGradle 的外网连接预检；依赖来自现有缓存。
本轮结果：

| 检查 | 结果 |
| --- | --- |
| 定向批量派发及部分执行回归 | 84 项全部通过，包含新增 10 项 |
| 完整 JUnit | 1,662 项：1,660 通过、2 跳过，零失败或错误 |
| 冷 JVM 专项 | CP-SAT 1 项、物料重放 20 项、补料 2 项全部通过 |
| 发行流程 Python 契约 | 12 项通过 |
| `check`、`build`、重混淆、`verifyReleaseJar` | 通过 |
| `compileGameTestJava` | 通过；本轮没有执行 GameTest 服务端 |
| 发行包审计 | 无重复 ZIP 条目或测试夹具；配置、唯一 refmap、许可证与 JarJar 元数据齐全；自有类为 Java 21 字节码 |

两项跳过分别缺少可选 EAEP SuperMatrix 制品和 Useless 实际接口制品。
公开接口描述符检查、对照测量与上述测试的原始记录都保留在本地忽略目录。

新版本坐标为
`com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1:2.0.4-gto.2`。
完整发行包的 SHA256 为：

```text
868167da2d511f3136dc6b72b5619e479117fdf656157055d69f1f33850b06c5
```

已发布到用户 Maven Local 与项目 `repo/`，两处 JAR 和冻结发行包的哈希一致。
发布任务重新打包后，对最终发行包再次核对了配置、refmap、许可证、测试夹具隔离
及 Java 版本；规范化常量池并忽略调试信息后，554 个自有类与完整检查产生的
slim 包一致。
真实 GTO 整合包的机器、专服、独立客户端及保存重启验收需使用该版本另行确认。
