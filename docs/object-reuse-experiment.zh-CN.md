# 对象复用实验：移植范围与 AE 附属兼容性

原版、Lean、最新 TB 的本轮完整重测见[最新完整对照表](object-reuse-latest-three-way.zh-CN.md)。

普通流体与 NBT 最新迭代的实现、867 项 JUnit / 31 项 GameTest 验证及三组独立 JVM 复测见[专项报告](object-reuse-fluid-nbt.zh-CN.md)。

最新三方实测见[原始基线／Lean 最新／当前 TB 对照](object-reuse-three-way.zh-CN.md)。

本实验基于 Thunderbolt `3798e5b0300eddb19706cd9352ba0a29f957048b`，目标是减少重复构造、组件 hash 和 NBT 复制分配，保留 AE2 及附属的物品身份、编解码、存储与样板语义。没有改变 V2 的搜索算法。

后续迭代已加入 Item 字段快路与双层弱缓存，设计、最新性能与兼容边界见 [Item 字段与双层弱规范化缓存](weak-canonical-cache.zh-CN.md)。下方性能表保留初版测量，不能当作当前结果。

设计参考 [nutant233/Lean-Object](https://github.com/nutant233/Lean-Object)：1.21.1 分支 `e001939d25eba950d49f5444ed98ea6947ea128d`、1.20 分支 `fbdf7df2efca291fabdb1e30226f784d3654d82c`。上游采用 LGPL-3.0-or-later。本分支重新实现兼容性边界，没有直接 cherry-pick 上游的全局替换，也没有引入 FastCollection 运行依赖。

## 实现范围

| 方向 | 本实验实现 | 兼容性边界 |
| --- | --- | --- |
| AEItemKey / AEFluidKey | Item/Fluid 字段保存普通键；组件流体保留 4096 槽有界缓存 | 保留 ItemLike.asItem、组件内容与值相等；Key 内计数／动画归一化为 1／0，原栈不变；堆叠上限随物品／组件快照复用，自定义耐久继续检查 |
| 带组件的物品键 | 写时复制 patch 身份索引，加内容相等的双弱引用规范化表 | 首次可写快照旁路；独立等值快照可汇合；原 AE2 值相等语义不变 |
| ResourceLocation | 有界构造复用；缓存原 hashCode | 保留校验、equals 和原 hash 值；构造复用默认关闭，适合重复标识多的负载 |
| TagKey | 复用构造候选对象并缓存原 hashCode | 候选仍进入原版弱驻留表，保留规范实例和既有相等语义 |
| CompoundTag.copy | 使用实际转换视图的 forEach，减少临时 Entry | 保留 HashMap、每个 Tag 的深复制和其他 mixin 传入的转换视图 |
| ListTag.copy | 原生 ArrayList 输入合并转换与收集，通用 Iterable 按原列表容量预分配 | 保留 ArrayList、原迭代顺序、元素复制和实际传入的 Iterable |
| ResourceKey | 保留原版弱驻留实现 | 已有去重，不再叠加另一套规范实例体系 |
| Ingredient | 保留原实现 | 不共享有可变 ItemStack 数组和懒缓存的 Ingredient，避免跨配方污染与重载问题 |
| 全局 NBT 容器替换 | 不移植 | 不要求其他附属的 NBT Map/List 必须为某个 fastutil 类型 |

上述固定 SHA 的上游 1.21.1 尚未包含 1.20 的模型、Farmer's Delight 等优化，这不是对上游后续版本的断言，不能按首页功能数推算移植覆盖率。登录时强制 GC／等待也未引入。

## 组件快照为何可以用来索引

原生 `PatchedDataComponentMap.copy()` 将原映射与新映射标记为写时复制；后续 set/remove/applyPatch 会分离底层 patch。本实验只把重复复制后得到的共享 patch 当作不透明索引，不修改它，也不把它当作内容相等的最终证据。

物品类型、组件内容或耐久元数据不同不能直接复用；输入计数、动画已归一化，不再拆分缓存。身份命中检查快照与耐久元数据，内容表以临时内容探针比较 Key 原有状态，弱持有规范 Key 及其已有 patch；AEItemKey 仅保留普通模板快照字段，不再持有组件 Entry；32 B 试验及恢复热路径字段的取舍见双层缓存文档。不同但等值的不可变快照可汇合。首次复制的快照避免身份与内容查询，降低一次性组件负载的缓存成本。一次组件变更后，首次转换会回到原生构造。

旁路、清空及普通槽的并发首次访问允许产生不同的等值对象。AE2 的 equals/hashCode 保持原实现；网络和存档继续使用原生编解码，构造出来的 Key 内部栈归一化为 1／0。缓存清空采用换代；清空前正在构造的线程只能写入旧表。身份表固定容量，组件内容表随存活对象增长、使用双弱引用并增量清理；服务端关闭、配置重载／卸载会清理。

堆叠上限仅在原生 Key 构造时计算；相同物品及组件下，不逐次探测外部配置／变量是否改变了上限。组件或模板变化仍按新快照查询，耐久核验保持。此次简化与实测见[堆叠上限缓存简化](object-reuse-stack-limit.zh-CN.md)。

这些优化要求调用者继续遵守 AE2 的只读键约定和 Minecraft 的组件不可变约定。如果某个附属依赖每次调用都执行键构造器的副作用，可单独关闭键复用；任意 mixin 组合无法仅凭接口约定保证兼容。

## 配置

`thunderbolt-common.toml` 的 `[objects]`：

| 配置 | 默认 | 作用 |
| --- | --- | --- |
| reuseAeKeys | true | AE2 物品／流体键构造缓存 |
| reuseComponentKeys | true | 允许重复的写时复制组件快照参与复用 |
| reuseResourceLocations | false | ResourceLocation 构造缓存；大量唯一标识时有额外成本 |
| reuseTagKeys | true | 原版 TagKey 驻留之前的候选复用 |
| cacheResourceHashes | true | 缓存 ResourceLocation、TagKey 的原 hash |
| fastNbtCopies | true | NBT 复制迭代和容量优化 |

通用构造和复制操作使用 WrapOperation；若其他附属移除了对应分配点，`require = 0` 允许该项优化不生效。普通物品字段及组件快路从工厂包装器直接返回，可能跳过其他附属在被包装工厂内部注入的副作用；网络、存档保留物品身份与组件，Key 内部数量／动画归一化；样板实际数量保持原清单。详细限制见双层缓存文档。

## 已验证的附属版本

Minecraft 1.21.1、NeoForge 21.1.219、AE2 19.2.17、Java 21。以下七个附属在同一隔离服务端联合加载，未加入 Lean-Object：

| 附属 | 版本 | 扫描的注册物品数 |
| --- | --- | ---: |
| ExtendedAE | 1.21-2.2.36-neoforge | 75 |
| AdvancedAE | 1.6.12-1.21.1 | 67 |
| NeoECOAE | 21.2.0-beta4 | 136 |
| AE2LT Reborn | 2.1.0，源码 5e12a0d7266c7dc68fdce9b628ec77af09750e3c | 186 |
| Data Energistics | 3.2.2 | 159 |
| ExtendedAE Plus | 1.6.2 | 49 |
| Useless Mod | 1.21.1-2.3.4 | 135 |

共 807 种注册物品，重复转换时 807 种均实际命中了缓存，且均通过身份、组件与编解码检查；对应文件的 SHA-256 见 [验证产物清单](object-reuse-tested-artifacts.json)。必要依赖包含 AE2AddonLib 1.0.3、Glodium 2.2、GeckoLib 4.9.2、LDLib2 2.2.40；AE2WTLib API 和 Configuration 由相应附属嵌入。该记录只覆盖以上版本，尤其不代表已经测试较新的 Useless Mod。

27 项运行测试包括：

- 原生 Mixin 生效、Block 类型 ItemLike、流体数量归一化、组件移除、耐久、计数与 popTime、并发、缓存清空和单独禁用。
- 每个附属物品在关闭／开启优化时的键值与元数据对照；默认／自定义名称和 NBT 两类身份；通用网络／存档编解码；10^60 数量存储中的组件区分和解码键提取。
- 用各附属材料编码 AE2 处理样板，再将样板键存档／网络往返并解码，确认两种组件输入及数量保持。
- DataAE 的 Data、DataFlow、Echo 与 LT 两种 LightningKey 的通用编解码。
- ECO 16M 物品元件、LT 无限存储元件实际插入／持久化／重载／提取；EAE 无限圆石元件使用解码键提取。
- V2 使用解码库存、区分组件库存；NBT 深复制与底层容器类型保持。
- GC 回收源栈与独立身份别名后，存活组件 Key 仍保持规范实例；Key 无外部引用时它与组件快照均能回收。
- 默认药水与空 patch 区分、独立等值组件快照汇合、数量／动画归一化且原栈不变，以及 Item 字段换代拆除；旧构造器跨清空晚返回时不能覆盖新代 Key 字段；不同默认组件模板的空 patch 和 8 线程数量／动画切换。

完整 JUnit 回归为 861 项，失败／错误／跳过均为 0。运行测试源集不进入发布 jar。

边界：这不是七个附属所有机器、CPU 调度、客户端 GUI 和长期整包运行的穷举证明。精简测试包中仍有可选配方缺少 AE2WTLib／AE2CS 的加载日志、LT 的旧掉落表引用，以及 Useless 2.3.4 指向旧 ECO 类名的警告；已用未加入本实验优化的原始 Thunderbolt 源码在同一附属组合下启动对照服务端，确认这些日志同样存在；它们不是本次移植新增的问题，但不能用“27 项通过”宣称整包日志无异常。

## 可复现命令

普通回归和微基准：

```sh
./gradlew test jar
./gradlew runKeyReuseGameTestServer -PthunderboltEnableAe2DevRuntime=true
./gradlew runKeyReuseGameTestServer -PthunderboltEnableAe2DevRuntime=true \
  -PthunderboltKeyReuseBenchmark=true
```

把七个附属及必要依赖的 jar 放进一个独立目录，再执行：

```sh
./gradlew runKeyReuseGameTestServer -PthunderboltEnableAe2DevRuntime=true \
  -I scripts/object-reuse-compat.init.gradle \
  -PobjectReuseCompatDir=/absolute/path/to/addon-jars
```

默认要求七个附属全部加载。测试其他组合时可用 `-PobjectReuseExpectedMods=extendedae,advanced_ae` 指定；缺失期望模组会让测试失败。目录中的源码 jar 被排除，所有附属均为测试输入，不嵌入本实验产物。

## 初版性能结果与限制

基准在实际 NeoForge/AE2 转换运行时执行，同一 JVM 中交替关闭／开启全部优化，6 轮预热、7 轮测量取中位数，结果写入 volatile sink。工厂每轮 200000 次，NBT 每轮 5000 次；分配量使用 ThreadMXBean。以下为一次记录，JIT／进程差异会影响绝对值。

| 场景 | 关闭 ns/次 | 开启 ns/次 | 关闭 → 开启 分配 B/次 |
| --- | ---: | ---: | ---: |
| 普通物品键 | 48.95 | 38.29 | 144 → 56 |
| 64 个物品的键 | 47.65 | 38.53 | 144 → 56 |
| 重复名称组件 | 80.71 | 40.97 | 288 → 56 |
| 重复复杂 NBT 组件 | 444.35 | 42.43 | 200 → 56 |
| 同物品 256 种重复组件 | 85.21 | 44.95 | 288 → 69.59 |
| 每次新建组件 | 120.00 | 113.92 | 624 → 624 |
| 普通流体键 | 8.84 | 8.53 | 72 → 48 |
| 1698 种物品轮换 | 61.74 | 60.86 | 144.04 → 95.36 |
| 重复 ResourceLocation | 53.41 | 54.55 | 144 → 120 |
| 唯一 ResourceLocation | 27.68 | 42.28 | 80 → 79.91 |
| TagKey | 12.21 | 12.83 | 24 → 0 |
| 64 项 CompoundTag 复制 | 1513.19 | 460.63 | 4336 → 2728 |
| 64 个子 CompoundTag 的 ListTag 复制 | 3451.58 | 1363.55 | 18944 → 13672 |

重复组件和 NBT 复制是明确的收益点。TagKey 主要减少分配；已有 ResourceLocation 的查询未测到明确收益。唯一 ResourceLocation 有退化，因此其构造缓存已设为默认关闭，并与 TagKey 分开控制。

初版缓存对一次性组件测到 115.63 → 173.23 ns 的退化，加入首次快照旁路后消除。不能只用高命中场景评价缓存。

这张表是同一带 Mixin 二进制的配置开关微基准，不是无 Mixin 原版的整体服务器对照；组件工厂约 10 倍的局部加速不等于 V2、机器吞吐或 TPS 提升 10 倍。

## 交付标识

实验 jar 名称为 `thunderbolt-object-reuse-experiment-2.0.0.jar`，显示名称带 Object Reuse Experiment。mod id 和版本仍为 thunderbolt / 2.0.0，以满足当前 LT 的精确 `[2.0.0]` 依赖；不发布到共享 Maven 本地库。仅保留在实验分支，稳定分支不受影响。

### 键数量与动画归一化

启用键复用时，普通键和组件键内部栈统一 count=1、popTime=0，不再按输入数量／动画区分缓存。原栈不被修改，`toStack(n)` 保留显式 n，实际存储、清单及执行数量保持原接口。归一化覆盖构造及网络／存档路径，数量相关的动态物品钩子基于归一化栈计算；禁用键复用恢复原生工厂行为。此处有意改变 `getReadOnlyStack()` 的数量／动画，不再把 Lean 的同类归一化列为兼容性失败。
