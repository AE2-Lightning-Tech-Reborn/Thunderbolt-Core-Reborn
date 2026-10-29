# GTO 通道换算率修复移植与实测（2026-10-20）

> 后续更新：已继续移植 ECO 任务归属与精确交接接口，最新交付包和实测见 [ECO 移植实测](GTO_ECO_PORT_20261021.zh-CN.md)。以下保留换算率修复阶段的结果与包哈希。

已将上游 GTL [`fdd766c07e4352e60516f4f0c75aa279f6113f49`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/fdd766c07e4352e60516f4f0c75aa279f6113f49) 中的换算率缓存失效检测、保存摘要刷新及回归测试适配到当前 GTO 项目。此前实际服务器报出的 `Existing handler retained stale channel units` 已在同一测试夹具上转为通过。

## 改动范围

- `ByteTracker` 为每个活跃 `AEKeyType` 缓存计算时的 `amountPerByte`，提供 `hasCurrentUnits()`；最后一个键移除时清理缓存，重建时更新全部换算率。
- `IndexedStorageCellInventory.ensureSync()` 同时检查库存修改计数和换算率。即使数量不变，字节查询、状态判断和有限盘容量模拟也会重建字节统计。
- `persist()` 在查询 SavedData 和判断是否需要保存数量前同步统计与摘要，覆盖仅换算率变化的情况。
- 移植上游九通道增长/移除回归，补充移除通道不再参与检测、重建清理、有限盘容量及摘要测试。

实现沿用当前按通道聚合的 fastutil Map，保留既有容量公式、126 位边界处理、GTO 注册持久化约定及无限盘大数量存储。上游完整提交中的存储布局、快照所有权、long 存取性能与生命周期优化仍是后续候选，本轮未整体移入。

## 回归测试

修复前先运行新增的三个库存测试，三个均复现失败：

| 断言 | 修复前结果 | 修复后 |
|---|---|---|
| 100 单位由每字节 8 改为 17，元数据占 8 字节 | 预期 14，仍为 21 | 通过 |
| 原先满盘在换算率变化后重新判断状态 | 预期 NOT_EMPTY，仍为 FULL | 通过 |
| 无数量变更时调用 persist 刷新摘要 | 预期 14，仍为 21 | 通过 |

修复后存储相关六个测试类共 **40/40 通过，0 跳过**：`BigIndexedStorageTest` 13 项、`ByteTrackerTest` 9 项、`ByteTrackerUnitReloadTest` 2 项、`IndexedCellStorageSavedDataMigrationTest` 3 项、`IndexedStorageCellUnitReloadTest` 3 项、`IndexedStoragePersistenceTest` 10 项。

有限盘测试还核对了换算率恢复、模拟不修改数量、实际存取与模拟一致、超容量时拒绝插入，以及有限/无限两种策略的保存摘要。`compileGameTestJava`、`verifyReleaseJar` 均成功。本轮没有重新运行完整规划器套件；此前全面测试的其他失败仍以原报告为准。

构建使用离线依赖及独立 D 盘报告目录。一次 ForgeGradle 环境联网证书探测停在 TLS 握手，线程记录已保留；随后只对本次离线构建关闭该环境探测，未修改项目构建配置或测试断言。

## 实际服务器

使用冻结发布包、新世界 `compat-world-infinite-unit-reload-port` 和原有 `full-qa-probe.jar`，没有修改测试夹具。环境保持 Java 21.0.10、Minecraft 1.20.1、Forge 47.4.20、GTOCore 26.10.5、AE2-GTO 15.2610.8、AE2LT 2.1.2-gto.1。

`poweredDriveSummaryAndPersistence` **通过**，覆盖真实供电驱动器、16,384 个带 NBT 的键、多个活动/闲置存储盘、512 位数量、原生/闪电/自定义通道、模拟存取、独立账本、SavedData 往返和换算率 `8→17→8`。包含原失败断言及换算率改变后的实际转移、摘要保存检查。

实际套件 **7/8 通过**，唯一失败为此前已记录的 `actualAppliedEModuleConsumesEmcAndFinishesOnPhysicalGtoCpu`，仍在 150 tick 超时。启动失败数为 0，服务器保存全部维度后正常退出，JVM 退出码 0；测试启动器因上述失败返回 1。因此本轮不能描述为整套服务器测试全部通过，也没有修复 AppliedE、终端精确缓存或 AE2LT 空矩阵加载等其他已知问题。

## 交付与证据

交付包：`build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`，与实际测试使用的冻结副本逐字节一致。

SHA-256：`b7ea74cb2f842f9bf885199dde450423d671bececb0fa0bf97fc4c822c3d75be`。发布包 610 个条目，不含测试探针、测试代理或 GameTest 类。

新证据目录：`D:/codex-builds/thunderbolt-unit-reload-20261020/`，包含修复前 XML、修复后 XML、构建日志、精确源码差异、源码快照、哈希和实际服务器结果。实际日志及世界保存在原隔离服务器目录，相关日志/结果另复制到新证据目录。修复前发布包和此前失败记录保留。

对应旧报告：[上游对比](GTO_UPSTREAM_COMPARISON_20261020.zh-CN.md)、[全面测试](GTO_FULL_TEST_20261020.zh-CN.md)。本报告只更新本次换算率修复的状态。
