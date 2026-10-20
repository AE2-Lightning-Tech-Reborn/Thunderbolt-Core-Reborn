# GTL 1.20.1 无限盘优化移植（2026-10-20）

移植来源为上游 `alpha` 的 [bbd8a95](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/bbd8a9505f2b2d687faacb0c96d716aceca0cb1c) 和 [f77f0cb](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/f77f0cb74a7430e48317d31dfc4b573a1b2471c3)，目标基线为 `fd3db3f`。原始优化包含 small-mumu 的贡献，汇总提交保留其协作署名。目标为 Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10 / Java 17。

## 变更与平台适配

- 普通 long 存取使用原始数值数组、类型槽位和最近键缓存；达到精确数量边界时才进入 BigInteger 路径。类型总量溢出使用稀疏精确账本，仍可在提取后恢复到 126 位以内。
- 大型精确快照通过 fastutil 的公开 clone/lookup/iterator 接口构造，独立持有数量数组。NBT 保存复用只读键编码，按字段失效缓存，隔离可变列表和数量数组。
- inventory 复用容量重建缓冲区和存档身份，按实际接收数量更新容量；自定义通道的 amount-per-byte 改变后重新同步。服务器停止时清除静态世界数据缓存，公共 registry 接口保留。
- AE2 15 使用 `toTagGeneric` / `fromTagGeneric`，ItemStack 使用 1.20.1 NBT，ListTag 使用旧版公开构造器。该平台所带 fastutil 的 `ensureCapacity` 非公开，未移植对它的调用。
- Forge 1.20.1 的 `SavedData.save(File)` 同步压缩写盘，并负责 `data` / `DataVersion` 外壳。本次保留该保存生命周期，通过 `save(CompoundTag)` 提供隔离快照；没有引入 NeoForge 的 I/O worker 或新增后台保存服务。

## 保留的本地修复

容量满字节中的剩余单位、126 位容量减法借位、类型上限检查、大数量和类型元数据的双重进借位均继续受原回归测试约束。损坏、截断和重复存档条目继续修复，并将修复结果标记为待保存；legacy 的单键 126 位上限、聚合溢出恢复、非精确盘只公开一段 long 可提取量的行为保留。

另补充了有限盘的模拟回归：legacy 单键距 126 位上限仅剩 3 时，直接套用上游 inventory 会返回可插入 10，实际引擎只能接收 3。新增测试先确认失败（expected 3 / actual 10），再恢复模拟调用引擎的数量边界检查；真实插入仍按实际接收量更新容量。

## 验证

| 任务 | 通过 | 跳过 | 失败 |
| --- | ---: | ---: | ---: |
| `test` | 1491 | 13 | 0 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 |
| `cpSatColdRefinementTest` | 1 | 0 | 0 |
| `materialDagColdReplayTest` | 20 | 0 | 0 |
| 合计 | 1551 | 13 | 0 |

全部 5 个任务均强制执行，未复用测试缓存；存储专项 61/61 通过。13 项跳过为缺少外部协议制品的 12 项 NeoECO 和 1 项 SuperMatrix 测试。 Forge 实服兼容与存储测试 9/9，生命周期 auto 与 always 各 11/11、11/11；发布脚本 12 项通过。验证期间源码指纹保持一致。详见[结构化结果](benchmarks/gtl-infinite-cell-port-20261020.json)。

百万次压测使用 4 个固定种子，每个 250000 次操作，3 个盘和 1024 个键（含 NBT 变体、水、熔岩），以独立 BigInteger 账本核对模拟、真实存取及盘间转移，合计 372 次压缩保存重载。文件测试使用 16384 个键和 12 代数据，核验 24 次 Forge 同步写入；测试独立持有的单线程 worker 延迟压缩旧快照，另核验 24 次写入及同目标最终代次。此 worker 仅属于测试，不是生产保存实现。

## 本机基准

| 键数 | 工作负载 | 基线两轮中位数（ns） | 移植后两轮中位数（ns） | 每次分配基线 → 移植后（bytes） |
| ---: | --- | ---: | ---: | ---: |
| 1 | `inventory_long_pair` | 476 / 747 | 35 / 43 | 2,632 / 2,632 → 48 / 48 |
| 1 | `engine_long_pair` | 193 / 243 | 8 / 8 | 2,360 / 2,360 → 0 / 0 |
| 1 | `exact_snapshot` | 32 / 48 | 38 / 40 | 272 / 272 → 224 / 224 |
| 1 | `world_save_sync` | 363,100 / 1,234,400 | 424,800 / 551,200 | 13,432 / 13,432 → 13,632 / 13,632 |
| 1024 | `inventory_long_pair` | 449 / 2,021 | 58 / 64 | 2,712 / 2,712 → 96 / 96 |
| 1024 | `engine_long_pair` | 275 / 1,328 | 22 / 24 | 2,616 / 2,616 → 0 / 0 |
| 1024 | `exact_snapshot` | 27,493 / 250,530 | 12,013 / 11,937 | 123,952 / 127,368 → 86,208 / 86,208 |
| 1024 | `world_save_sync` | 2,193,700 / 7,956,600 | 2,213,700 / 2,206,500 | 136,448 / 136,448 → 142,536 / 142,536 |
| 16384 | `inventory_long_pair` | 570 / 1,254 | 103 / 152 | 2,744 / 2,744 → 128 / 128 |
| 16384 | `engine_long_pair` | 370 / 874 | 69 / 72 | 2,616 / 2,616 → 0 / 0 |
| 16384 | `exact_snapshot` | 1,181,760 / 2,082,780 | 158,113 / 190,517 | 2,028,288 / 2,045,320 → 1,376,448 / 1,376,448 |
| 16384 | `world_save_sync` | 21,288,400 / 27,204,900 | 22,153,900 / 28,376,200 | 13,344 / 13,344 → 142,312 / 142,312 |

表中保留两个 JVM 的各自中位数，未选择最快一轮作为结论。普通存取和精确快照减少了分配；同步写盘包含额外的快照复制，其分配量增加。不同规模和轮次可能出现计时回退，不能声称所有负载都更快。完整 9 类负载和样本范围见[基准数据](benchmarks/gtl-infinite-cell-benchmark-20261020.json)。

## 复现

```powershell
.\gradlew.bat check verifyReleaseJar --rerun-tasks --no-build-cache --continue --offline --no-parallel --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false --console=plain
.\gradlew.bat -I scripts/benchmarks/cell-stress.init.gradle test --tests 'com.moakiee.thunderbolt.core.storage.cell.IndexedCellConservationStressTest' -x externalPlannerBoundaryTest -x materialDagLargeGraphTest --offline --no-parallel --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false --console=plain
.\gradlew.bat -I tools/gtl-compatibility-game-tests.gradle runGameTestServer --offline --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false --console=plain
python scripts/benchmarks/indexed-cell-benchmark.py --baseline fd3db3f --offline --output build/indexed-cell-port-benchmark.json
```

基准使用 Java 17 的 `JAVA_HOME`，每个版本两个独立 JVM，按 baseline→candidate、candidate→baseline 顺序运行；每个任务预热 3 次、测量 7 次，并在计时区外核对库存。`world_save_sync` 包含 Forge 的同步编码、压缩和文件写入，不能与上游 NeoForge 的 enqueue 计时混用。计时波动和微基准不能推导整包 TPS。

## 发行包

`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，5413296 字节；主包 529 个类均为 Java 17 字节码，内嵌依赖未超过 Java 17，未混入 GameTest 或兼容夹具，Mixin 配置和 refmap 已核对。SHA-256：

```text
dd4606f965b48d790ff206dbd1ef60c21da8b6c9f4c1c0c5014249f5173fc613
```

原始日志、测试 XML、本机路径及临时运行器留在忽略目录。完整 GTL 整合包与客户端渲染不属于本轮验证范围。
