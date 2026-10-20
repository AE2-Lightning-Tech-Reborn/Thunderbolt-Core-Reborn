# Indexed cell review and conservation stress — 2026-10-19

## 范围与结论

- 仓库：`AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn`。
- 审查基线：`5128e72`（当时的 `alpha`）。
- 被审查提交：`12dc97f`，`perf/infinite-cell-reviewed`。
- 合并目标：`alpha`。本次差异仅涉及 indexed cell；不包含新的规划器修改。
- Minecraft 1.21.1、NeoForge 21.1.219、构建依赖中的 AE2 19.2.17。

在本次代码审查、独立账本压测和真实 NBT 文件写入测试的范围内，未发现阻塞合并的丢物或刷物问题。常规 long 存取绕过 BigInteger、避免重复序列化上下文和复用不可变键快照有实际收益。维护复杂度由中等升至中高，但状态仍集中在存储层，有针对性的边界和生命周期测试，可以接受。

这些测试直接执行生产存储类、AE2 key codec 和 NeoForge I/O worker；不等同于完整整合包里的 ME 网络、跨维度卸载或断电恢复验证。磁盘 I/O 错误处理沿用 NeoForge `SavedData` 的语义，不提供跨背包文件与世界存储文件的事务保证。

## 正确性审查

1. **数量边界**：单 key 的 exact 数量上限是 `2^16384 - 1`；普通 long 路径在类型总量即将越过 126 位投影范围时转交 exact 路径。`slotExact` 表示类型总量已溢出，`exactPathPossible` 是保守的快速判断，不能当成“所有 key 都是大数”。类型总量是精确数量的投影；库存不能从该投影反向恢复。
2. **返回值就是实际数量**：inventory 依据 `storage.insert()` 的实际返回值更新字节数和摘要；接近 exact 上限时不会把请求量误报成接收量。提取同理。模拟不会改变库存、modCount、保存回调或物品摘要。
3. **索引生命周期**：同值不同对象的 key、未命中缓存、删除后 ID 复用、数组增长、压缩重编号及 load 均有覆盖。`slotOfId` 必须跟随 key 的 ID 一同移动；数量索引、类型计数和稀疏大数账本必须同步更新。
4. **多观察者与单位变更**：inventory 用 modCount 检查共享 engine 的外部修改；ByteTracker 同时检查每个存活 channel 的 amount-per-byte。移除类型及超过两个 channel 时的 map 切换有独立测试。
5. **异步保存所有权**：只共享在 key 生命周期内不再修改的编码条目；数量数组、可变列表和大数字段需要脱离 live payload。金额修改、63 位进借位、类型增删、ID 复用、压缩和 reload 必须失效对应快照缓存。
6. **文件保存协议**：核对了实际 NeoForge 21.1.219 的 `SavedData.save(File, Provider)` 字节码和 `IOUtilities` 源码。分支保留 `data` / `DataVersion` 外壳、串行 I/O worker、压缩写入和原子替换，仅替换深复制策略。停服事件清除 per-server 缓存，缓存命中还校验 server 对象身份。
7. **编码兼容**：核对了依赖 JAR 中 `AEKey.fromTagGeneric` / `toTagGeneric` 的字节码。新路径仍调用同一 `AEKey.CODEC` 与 registry serialization context，保留同样的解码失败行为；优化在于复用 context。

## 复杂度与维护成本

令 `N` 为存活 key 数，`S` 为已分配 key 槽数（含洞），`C` 为 channel 数，`D` 为上次保存后的脏槽数，`H` 为 key 哈希表容量，`B` 为 exact 数量的位数，`W` 为稀疏大数条目数，`M` 为世界存储中的盘数。

| 路径 | 时间复杂度 | 说明 |
| --- | --- | --- |
| 普通 engine 存取 | 平均 O(1) | 哈希查找、数组计数和双 long 运算；扩容为摊销成本 |
| inventory 普通存取 | O(C) + 摘要更新 | 每次检查 channel 单位；C 通常很小，但不能声称严格 O(1) |
| 大数存取 | O(B) 数值运算 + 平均 O(1) 索引 | B 有每 key 的 16384 位边界；类型总量可能再多几个计数位 |
| ByteTracker rebuild | O(C) | 不随 N 扫描物品；已出现的类型槽在 engine 内可保留 |
| 枚举 | O(S) | 输出 N 个 key；延期压缩前可能经过空洞 |
| exact snapshot | 稠密 O(H)，稀疏 O(S) | 必须分配脱离 engine 的数量/索引；不是零成本视图 |
| 增量编码 | O(D)，大数编码另计 | key 结构不变时复用已编码 key；压缩/全量写入回到 O(S) |
| 异步快照准备 | O(M)，另加每个变更盘 O(S + W 的编码体积) | low 数组仍需复制；key 列表和 high 数组可复用；未变更盘复用已脱离的快照 |
| 实际文件压缩/落盘 | O(全部存储编码体积) | 在 I/O worker 上运行；优化不代表磁盘文件变成增量写入 |

需要持续维护的约束：

- `IndexedStorage` 不支持并发写。查询缓存、数组、脏队列和序列化 context 都属于服务器线程；后台线程只能读取已交接的快照。
- `KeyIndex` / `ExactSnapshot` 依赖 fastutil 的 protected `key/value/mask` 布局和探测规则。升级 fastutil 时必须重跑 Map 契约、同值 key、删除重用和快照隔离测试。
- key NBT 条目是“按约定不可变”，Java 类型本身不是不可变类型。未来新增修改 key 标签的代码必须整体替换条目，不能原地修改旧条目。
- 四组状态（key 数量、类型聚合、字节摘要、保存缓存）需要一致。新增修改入口应调用现有生命周期函数，不能绕过 modCount、dirty queue 和 snapshot invalidation。
- 两个 ByteTracker rebuild 入口有重复算术；目前保留兼容入口，不为此次优化再引入抽象层或更大重构。

## 新增压测

`IndexedCellConservationStressTest`：

- 四个固定随机种子，每个 250,000 次，合计 **1,000,000 次操作**。
- 每轮 3 个盘、1,024 种 key，混合 NBT 物品及水/岩浆。
- 独立 `BigInteger[]` 账本核对每次返回值和受影响 key；定期核对全部库存。
- 覆盖模拟/实际插入和提取、盘间部分接收、同值 key 别名、删除与 ID 重用，以及 63 / 126 / 512 / 16384 位边界。
- 经过真实 NBT 压缩解压后在原 engine 上 load；合计 **372 次保存重载**。
- 按每种 key 检查 `累计输入 = 三盘剩余 + 累计输出`，未发现不一致。
- 另用 16,384 种 NBT key，阻塞 I/O worker 后排入 12 代、24 次真实文件写入。期间强制删除超过三分之二的 key 触发压缩，并继续复用 ID、改变高位和大数数量；释放 worker 后每个历史文件与独立账本一致，同一路径的最终文件为最后一代。

复现：

```bash
./gradlew test --tests '*IndexedCellConservationStressTest' \
  -I scripts/benchmarks/cell-stress.init.gradle --console=plain
```

扩大规模可增加 `-PcellStressOperations=... -PcellStressKeys=...`。operations 是每个种子的次数；默认有四个种子。扩展压测使用 2 GiB 测试堆。

## 性能对比方法

原始数据见 [indexed-cell-review-20261019.json](indexed-cell-review-20261019.json)。

使用同一份依赖和 harness，在独立 JVM 内分别载入基线与候选的四个 storage 类。基线源码直接取自 `5128e72`，没有改写。两个 JVM 对的顺序分别为 baseline→candidate 和 candidate→baseline，降低热缓存与执行顺序影响。每个指标预热 3 轮、采样 7 轮，记录每轮 JVM 的中位数/最小值/最大值和调用线程分配量。小盘快照/枚举增加到每轮 30,000 次调用，避免只有几十次调用时微秒级计时噪声占主导。

基准包含 1 / 1,024 / 16,384 个 NBT key；每个时段外都核对库存、枚举或真实存档，防止把少做工作当成加速。存取 pair 指一次插入加一次提取。`world_save_enqueue` 只测服务器线程准备快照和入队耗时，I/O 完成和文件校验在计时区间外。

```bash
python3 scripts/benchmarks/indexed-cell-benchmark.py \
  --baseline 5128e72 \
  --output docs/benchmarks/indexed-cell-review-20261019.json
```

需要 Java 21，设置 `JAVA_HOME` 后执行。结果是微基准，不可直接换算成整合包 TPS。

## 性能结果

环境：GraalVM Java 21.0.12 / aarch64，`-Xms512m -Xmx2g`。下表是两个独立 JVM 中位数的中位数；完整分布和分配量在 JSON 中。

16,384 个 NBT key：

| 指标 | 基线 | 候选 | 基线 / 候选 |
| --- | ---: | ---: | ---: |
| inventory long 存取 pair | 1,652.9 ns | 303.0 ns | 5.45× |
| engine long 存取 pair | 1,404.3 ns | 110.1 ns | 12.76× |
| 模拟插入/提取 pair | 909.2 ns | 87.9 ns | 10.35× |
| 全量 exact snapshot | 3,703.0 µs | 105.1 µs | 35.25× |
| 枚举到新 KeyCounter | 2,840.6 µs | 1,744.2 µs | 1.63× |
| 128 个 key 各存取一次再增量编码 | 137.3 µs | 7.5 µs | 18.34× |
| world save 准备与入队 | 2,361.6 µs | 99.1 µs | 23.82× |

同一负载下，inventory pair 的调用线程分配量从约 10,464 B 降至 644 B；world save 准备从 10,226,600 B 降至 132,816 B。这里统计的是分配量，不能用作长期驻留堆大小的测量。

1,024 key 的 inventory pair 约快 6.06×，单 key 约快 9.13×。并非所有小操作都更快：单 key exact snapshot 从 45.6 ns 到 48.9 ns，枚举从 34.5 ns 到 38.9 ns，且后者分配量由 912 B 到 928 B。绝对差异仅数 ns，记录为小盘路径退化，不据此宣称全面加速。单 key engine pair 易受 JIT 对重复操作的优化影响，不把其极高倍率用作实际网络收益依据。

## 构建验证

执行：

```bash
./gradlew build -I scripts/benchmarks/cell-stress.init.gradle --console=plain
```

- 176 个测试类，共 1,358 项：**1,356 通过，0 失败，0 错误，2 跳过**。
- IndexedStorage / IndexedCell / ByteTracker 相关 **36 项全部通过**，包含上述 5 项新增压测。
- 两项跳过分别为 Useless 实际 API 和 ExtendedAE Plus 实际类兼容测试；原因是本次未提供对应可选测试模组，均不涉及本次存储修改。
- 产物 `thunderbolt-2.0.3-beta.jar` 构建通过，ZIP 完整性校验通过。
- SHA-256：`24c83ae86eb66c6019edc256074dd82cf36c4ddbb2db2dc27c2fa3bd25e44bb2`。

被审查的生产实现未在本轮改写；新增内容为独立账本与文件队列压测、可复现基准和本记录。合并按仓库约定整理为一个汇总提交，并保留原优化作者署名。
