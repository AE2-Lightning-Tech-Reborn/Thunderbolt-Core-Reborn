# 普通流体与 NBT 复制优化复测

原版、Lean、最新 TB 的本轮完整重测见[最新完整对照表](object-reuse-latest-three-way.zh-CN.md)。

2026-09-19，在 `experiment/object-reuse-20260919` 上继续优化；实现文件与测量产物由附表中的 SHA-256 固定。改前固定为 `939892da5e4cacbbace6e933f89f1d45a50c7592`，从该提交导出独立干净目录进行复测。Lean 固定为上一轮同一份 `22d4c0930353b4904a75499f47f25d876e26b2ef` 及其已构建 jar。本表没有重新定义或覆盖[此前三方报告](object-reuse-three-way.zh-CN.md)。

## 本轮测量

沿用已提交的外部混合基准，源码未改。三组各启动三个独立 JVM，顺序为 BEFORE→LEAN→AFTER、LEAN→AFTER→BEFORE、AFTER→BEFORE→LEAN，顺序执行，避免并发争抢 CPU。每个 JVM 6 轮预热、7 轮测量；工厂每轮 20 万次，NBT 每轮 5000 次。先取进程内中位数，再取三个进程的中位数。结果写入 volatile sink，分配量由 ThreadMXBean 统计当前线程。

环境仍为 Minecraft 1.21.1、NeoForge 21.1.219、AE2 19.2.17、Temurin 21.0.11+10、arm64。性能进程没有加载七个附属；附属兼容验证单独运行。

每格为 **ns/次；分配 B/次**。改善指相对本轮改前的耗时减少比例。

| 场景 | 改前 TB | Lean | 改后 TB | 耗时减少 |
| --- | ---: | ---: | ---: | ---: |
| 普通流体键 | 6.31；24.00 | 2.67；0.00 | 2.68；0.00 | 57.5% |
| CompoundTag：64 字段 | 427.03；2728.01 | 459.69；1696.01 | 204.63；2640.01 | 52.1% |
| ListTag：64 个单字段 CompoundTag | 1260.99；13672.01 | 1200.83；12112.01 | 978.73；8000.01 | 22.4% |

三个 JVM 的耗时范围：

- 普通流体：BEFORE 5.97–6.36 ns；LEAN 2.62–2.68 ns；AFTER 2.67–2.88 ns
- CompoundTag：BEFORE 419.63–442.36 ns；LEAN 417.74–508.48 ns；AFTER 189.39–293.42 ns
- ListTag：BEFORE 1173.9–1329.98 ns；LEAN 937.72–1306.36 ns；AFTER 963.98–1349.07 ns

普通流体已接近 Lean。ListTag 本轮中位数改善约 22%，但范围与改前、Lean 均有重叠，其中一次 AFTER 也慢于同轮 BEFORE。JIT 内联和逃逸分析会影响 NBT 的时间与分配，不能据此保证每个进程都领先。ListTag 的 AFTER 分配范围为 8000.01–11072.01 B，BEFORE 为 8000.01–13672.01 B。CompoundTag 当前分配仍高于 Lean；这些是微基准，不是整包 TPS 或合成规划速度。

## 实现

普通流体在 Fluid 上直接保存普通 AEFluidKey，在命中前避免复制 FluidStack，也避免共享散列槽查询。FluidStack 入口仅接收没有组件补丁且 prototype 确为 DataComponentMap.EMPTY 的普通栈；自定义 prototype 和带组件流体继续走有匹配校验的路径。数量仍不属于键，零量、负量、EMPTY 仍返回 null。Fluid 入口也可以直接命中。

每个 Fluid 增加两个引用字段；实际用到的普通流体另有一个缓存代际对象。AEFluidKey 本身没有增加字段。重置、关闭功能时解除 Fluid 引用，并拒绝旧代际的迟到发布。组件开关、值相等、hash、存盘和网络协议保持现有实现。

NBT 在 copy 内部使用短生命周期的惰性转换视图，将实际收到的转换函数与输出收集过程合并。Map 复制减少 Guava 转换回调的中间层；List 复制直接遍历源迭代器并应用转换函数，避免再套一个转换迭代器。此路径只用于原生精确 HashMap/ArrayList 输入；自定义容器走原有通用路径。

输出仍是原生 HashMap/ArrayList，不替换整个 NBT 存储容器，不缓存可变 NBT，不改 Tag.copy 的动态派发。不可变元素继续按原版规则共享，可变后代独立深复制。外部替换了收集步骤收到的 Map/Iterable 时，收集器使用实际收到的视图。优化涉及 copy 中的 Guava 调用点；对另行修改相同调用点的优化模组，仍应单独联测。

## 验证和证据

- 867 项 JUnit 全通过，无失败或跳过。
- 同时加载 EAE、AAE、ECO、LT、DataE、EAEP、Useless，31 项真实 Mixin GameTest 全通过；覆盖 807 种附属物品及全部 12 种已注册流体。
- 新增流体早期查询、数量与空栈、调用者修改、自定义 prototype、组件关闭、缓存重置与旧代际发布测试。
- 新增 NBT 转换函数、惰性视图、顺序、删除操作、异常后重试、覆盖 Tag.copy、自定义 LinkedHashMap/LinkedList、不可变元素和深复制隔离测试。
- 原有物品键、存储、序列化、GC 和并发测试通过。基准附带语义探针中，BEFORE/AFTER 的输入匹配、药水、动态属性、并发相等及存盘/网络观察一致。

[结构化样本、源码哈希、jar 哈希和验证摘要](object-reuse-fluid-nbt-results.json)保存九个进程的全部结果及日志哈希；[附属版本与文件哈希](object-reuse-tested-artifacts.json)沿用已验证组合。完整开发日志保留在本机，不加入仓库。通用复现方式见[基准说明](../scripts/benchmarks/object-reuse/README.zh-CN.md)，将 comparisonMode 分别设为 BEFORE、LEAN、AFTER；仅 LEAN 加载 Lean jar，BEFORE 与 AFTER 使用对应源码目录。
