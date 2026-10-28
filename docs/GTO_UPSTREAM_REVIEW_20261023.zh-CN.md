# 上游检查（2026-10-23）

已联网查询上游，并使用明确列出的分支 refspec 执行 `fetch --no-tags`。本轮检查 `gto`、普通 `1.20.1`、`main` 和 `alpha`，不查询或比较 GTL。继续排除 NeoECO。本轮只刷新远程跟踪引用和记录源码差异，没有移植、构建、运行游戏测试、提交或推送；现有交付 JAR 保持原状。

## 分支状态

| 分支 | 当前提交 | 相比上轮 |
| --- | --- | --- |
| `gto` | `7c7a066af9d404010fe0b9472fca687879b36281` | 无更新，与本地 HEAD 相同；本地另有尚未提交的移植与兼容修复 |
| 普通 `1.20.1` | `cea4cc29b97e308987ad4a06a2c61f7fb00f0527` | 无更新，通用更新已按此前报告移植，NeoECO 仍排除 |
| `main` | `bb2f2ab0ad48ef3b53087327fe833d4e1743eb98` | 无更新 |
| `alpha` | `1e6eee73c2d0bfe588b8dbbf8dd96feb765007dc` | 从 `28f5f8f` 新增 4 个提交 |

## Alpha 新增内容及本地状态

`alpha` 目前为 Minecraft 1.21.1 / NeoForge 21.1.219，仅作为实现参考。四个提交合计涉及 13 个生产 Java 文件，另外包含回归测试、基准驱动和说明。

| 提交 | 实际变更 | 当前 GTO 项目 |
| --- | --- | --- |
| [`6916487`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/6916487c9b6cf0ebabefc7016c0f1a8110318ef4) | 同 ID 超载副产物接续；发现晚到副产物时在原预算内重建图，补齐 ID_ONLY 的晚绑定副产物连接，精确诊断选路计入副产物余量 | 尚未移入，优先级最高 |
| [`69e9af1`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/69e9af1b35b533f4593c122eff7b46a3ee4c37cf) | 减少 CPU 集群稳定刷新和样板解包分配；单次大数执行认证复用配方摘要；共享输入复用已读槽对象；线性选择共享供应器 | 尚未移入，适合按模块选择性移植 |
| [`a6eb45d`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/a6eb45d11c766856e361936090c505f2ac572227) | 候选队列减少哈希查询、延迟创建拒绝容器；减少供应器快照中间列表；普通精确预览走有界整数路径；long 范围精确存取复用原整数账本 | 尚未移入，需保持现有大数、容量和单位重载语义 |
| [`1e6eee7`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/1e6eee73c2d0bfe588b8dbbf8dd96feb765007dc) | 单次字节增减量超出 63 位时保留进位；规划器撤销记录改用可复用、单段最多 1024 项的分段存储 | 字节进位本地已有等效修复；分段撤销存储尚未移入 |

### 优先候选：同 ID 副产物

本地 `FastCraftingPlanner.PrimaryIdentityCraftables` 仍只按主产物目录建立索引，没有收集可达配方的副产物，也没有上游新增的 `beginPass` / `hasLateByproducts` 重建判定。`ExactDiagnosticPlanner` 选替代材料时仍只读取 `capacity`，没有叠加已产生的 `surplus`。

因此上游修复针对的源码缺口在当前项目仍存在：当下游允许同 ID 忽略组件/NBT，材料仅作为可达配方的副产物供应时，可能漏掉接续路线或在超大数量预览中误报缺料。这是源码对照结论，本轮没有在当前 GTO 游戏环境重现，不将上游测试视为本地验证。

移植时需保留当前 GTO 的模拟库存类、原生任务回退、图构建工作预算、完整 AEKey 的物理库存身份，以及 STRICT 输入不能使用未知组件产物的约束。上游索引只收集本次可达配方的副产物，不遍历全网无关配方。

### 字节进位已等效处理

本地 `ByteTracker.onInsert` 已在累加前将负的单次 `byteDelta` 拆为高位进位和低 63 位，`onExtract` 对 `bytesFreed` 做对应借位处理，再单独处理低位加减的进借位。该实现已覆盖上游此次修复的目标。

已有 `ByteTrackerTest` 包含一次插入同时发生单次增量和累计低位进位，以及超大删除量的借位边界测试。因此不能把 `alpha` 的这四行改动描述成本地仍未修复的容量 Bug，也不应机械叠加第二次进位。

### 其他优化的移植边界

- 供应器代码需保留当前 `PreparedBatch` 的预准备、容量变化和提交行为。
- 候选队列改动需验证迭代过程中屏蔽节点、成功优先和跨 tick 恢复。
- 精确预览和存储快速路径需保留有符号外部计划、BigInteger 回退、模拟不变性、单位换算重载、保存快照与数量守恒。
- 分段撤销存储减少每次写入创建闭包及扩容复制；1024 是单段大小上限，不是全部搜索记录或总内存上限。需验证完整回滚、availabilityState、取消和预算耗尽后的重试。
- 上游性能数据来自其指定的 1.21.1 基准，部分小案例存在退化，不可直接折算成当前整包 TPS 收益。

上游 `1e6eee7` 提交说明记录了 174 项定向测试和 14 项 NeoForge 存储/Mixin GameTest 通过，同时说明完整 Gradle 验证受锁套接字限制，以及先前全量 JUnit 存在已复现的测试顺序问题。本轮不将其表述为最新 alpha 已完整全量通过。

## 本轮证据

`build/review/upstream-review-20261023/source-comparison.json` 记录了四个分支完整提交、alpha 新增提交列表、13 个生产文件与旧版/新版的内容比较及本地哈希。比较读取当前工作区文件，包含未提交和未跟踪文件，统一 CRLF/LF 后比较，没有仅凭本地 HEAD 判断是否已移植。

Git 网络命令通过单条命令的 `-c http.proxy= -c https.proxy=` 直连，未改变全局代理配置。未读取或更改其他项目文件夹，未修改 AGENTS.md。
