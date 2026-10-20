# Indexed cell maintainability follow-up — 2026-10-19

基线是已完成审查的 `alpha` 提交 `bbd8a95`。本轮只重构存储层；精确数量上限、long 快速路径、数量算术、对外 API 和存档格式均保留。

## 减少维护约束

- **ExactCellSnapshot**：使用 fastutil 的公开 `clone/getInt/fastIterator`。删除 `KeyIndex` 子类、protected 数组访问和手写哈希探测。克隆的 key→ID 索引与独立数量数组组成快照，原库存的删除、扩容、压缩和重载不能改变旧快照。
- **ByteTracker**：四组必须同步扩容/搬移的并行数组合成一组 `Channel` 记录。两个 rebuild 入口共用重置、单 channel 计算和类型开销计算，舍入与进位只有一份实现。普通存取仍使用原增量算法，记录只在 channel 创建或重建时分配。
- **CellNbtSnapshot**：集中处理哪些 NBT 字段可以共享、哪些必须复制。key/high 的缓存失效发生在增量 persist 实际改写编码数据的位置，load 统一 reset；删除 allocate/recycle/compact 中分散的 key 快照失效调用。全量改写与数组增长由 source 身份变化处理。

`IndexedStorage` 从 1003 行缩至 911 行；五个相关生产文件总行数从 1591 到 1579（含新增小类）。主要收益是减少内部依赖和跨方法约束，而不只是行数变化。逐物品数组、类型聚合、modCount、脏队列仍有固有复杂度；后台线程仍只能读取已交接的快照，不能并发修改 engine。

## 性能

[原始数据](indexed-cell-maintainability-20261019.json) 使用与上轮相同的 Java 21 / aarch64 环境、独立 JVM、反向运行顺序、每 JVM 3 次预热及 7 次采样。基线四个类取自 `bbd8a95`；新增空盘增删和 512 位精确存取负载，覆盖 channel 记录的创建/重建成本。JSON 同时记录生产源码与 harness 的 SHA-256。

16,384 种 NBT key，两个 JVM 中位数的中位数；负数表示耗时减少：

| 指标 | 基线 | 重构后 | 变化 |
| --- | ---: | ---: | ---: |
| 完整 long 存取 pair | 187.1 ns | 188.9 ns | +1.0% |
| engine long 存取 pair | 77.5 ns | 77.1 ns | -0.5% |
| 模拟存取 pair | 69.5 ns | 68.2 ns | -1.8% |
| exact snapshot | 100.9 µs | 64.6 µs | -35.9% |
| 枚举 | 1304.4 µs | 1320.0 µs | +1.2% |
| 128 key 存取后增量编码 | 7.23 µs | 5.80 µs | -19.8% |
| 存盘准备与入队 | 84.88 µs | 88.85 µs | +4.7% |
| 空盘增删 pair | 159.0 ns | 132.4 ns | -16.7% |
| 512 位精确存取 pair | 433.0 ns | 424.5 ns | -2.0% |

代价也保留在数据中：该负载的 exact snapshot 分配量约增加 5%（1,310,840 → 1,376,448 B）；512 位存取约增加 4%（2,196 → 2,284 B）。新增 channel 记录的分配集中在结构变化/重建，不是每次普通存取都分配。

并非所有尺寸都改善：1,024 key 的增量编码 +16%，空盘增删 +8%；1 key 模拟从 8.3 ns 到 13.7 ns，增量编码从 83.1 ns 到 94.4 ns。这些小时间区间存在 JIT/采样波动，不能只选最快数据宣称全面无损。主要存取路径保持接近基线，保留了前次优化的主要收益。

## 正确性验证与复现

复用原有独立账本、Map 契约、同值 key、删除复用、channel 单位变化、高位进借位、压缩和快照隔离测试。修改的两条测试调用现在通过完整 NBT 快照入口观察 key 列表，断言强度不变。

百万次操作、372 次 NBT 保存重载及 24 次排队文件写入均通过，未观察到丢物/刷物。测试范围仍是生产存储类与真实文件 I/O，不代表完整整合包长时间运行或断电事务验证。

```bash
./gradlew build -I scripts/benchmarks/cell-stress.init.gradle --console=plain
python3 scripts/benchmarks/indexed-cell-benchmark.py \
  --baseline bbd8a95 \
  --output docs/benchmarks/indexed-cell-maintainability-20261019.json
```

使用 Java 21，并设置 `JAVA_HOME`。

完整构建通过：1,358 项测试中 1,356 通过、2 项可选兼容测试跳过，0 失败、0 错误；存储相关 36 项全部通过。
