# GTL 1.20.1 全面检查与修复（2026-10-19）

检查基线为 `1.20.1_GTL` 的 `a305f6fbe8f118dc780e0d215dc53d9536fbda0e`。环境为 Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10 / Java 17。

本轮在此前兼容修复的基础上，扩大检查到有限容量存储、世界持久化、弹出端点生命周期、规划可执行性、批量派发回滚、通道分配及并发查询。确认并修复三类缺陷；另外保留完整套件出现的失败记录，不能据此宣称所有 Bug 均已排除。

初轮检查于 2026-10-20 完成。最终 `check verifyReleaseJar --continue` 成功，各任务报告合计 1525 项通过、13 项跳过、0 失败；实际 Forge 回归 29 次、压力测试 27 个 JVM 全部通过。早先修复后代码仍出现过冷启动预算断言失败，其间歇性原因尚未定位，不能用最后一次通过将其视为已解决。后续关闭构建缓存的强制检查、33 个全新 JVM 复测和第二轮 Forge 回归全部通过，详见[复验报告](gtl-reverification-20261020.zh-CN.md)。本文件保留初轮的原始结果与限制。

## 有限容量存储的边界与大数量记账

`ByteTracker.computeMaxInsertable` 原先在字节数相等时直接拒收。例如容量为 1 字节、每字节可容纳 8 件物品，已有 1 件时仍应容纳 7 件，原实现返回 0。

容量高位大于已用高位时，原实现直接返回 `Long.MAX_VALUE`，既没有处理低位借位，也没有执行类型上限检查。例如容量为 `2^63 + 5` 字节、已用为 `2^63 - 3` 字节，原实现仍放行 `Long.MAX_VALUE`，正确余量只有 8 字节。

此外，大数量材料与新增/删除类型的元数据开销相加后可能超过一个 63 位分段。原先只检查累计结果的符号，会漏掉额外进位或借位，导致后续容量判断错误。

修复后的插入检查统一执行类型限制、126 位容量减法和类型元数据扣除，再考虑已分配字节的剩余单位，并对 AE2 的 long 返回值饱和处理。增减记账分别处理数量变化自身和累计结果的进位/借位。

新增 6 项 JUnit 回归，其中容量模型使用独立的 `BigInteger` 闭式公式进行 6048 次对拍，覆盖物品/流体单位、类型开销、类型上限、已满/超限、63 位边界和多分段容量。修复前最初新增的 4 项均失败；另两项锁定大数量增减的进位/借位。

## 弹出端点关闭误删持久化记录

原先关闭一个注册句柄，会删除同一拦截维度、位置和方向的全部持久化记录。运行时索引可能仍保留其他有效端点，但其记录已经无法保存，重启后会丢失。

实际 Forge GameTest 复现了三种情况：同一位置/方向的不同主机、同一端点的重复句柄，以及显式注销后旧句柄误删新注册。修复前新增的 3 项全部失败，同时原有 4 项兼容测试通过。

修复后，只删除已实际移除的句柄对应的具体持久化记录；相同记录仍有其他句柄时继续保留。索引中已经不存在的旧句柄不执行删除操作。新增测试位于 `src/compatibilityGameTest/java/com/moakiee/thunderbolt/gametest/EjectLifecycleTests.java`，可复用已提交的隔离 Forge 测试入口。

## 无调用者截止时间时的小图见证计时不一致

CP-SAT 的小图恢复是纯 Java 搜索，但原先位于原生求解的墙钟预算内，与相邻种子恢复使用的 CPU 计时不同。没有调用者截止时间的直接调用，也会把线程暂停时间计入小图恢复预算，提前放弃可以完成的搜索。

修复将小图恢复放回 Java 见证阶段，共用已有 CPU 计时和会话余量；存在调用者或外层墙钟截止时间时仍遵守原限制，预算数值和测试断言没有放宽。

新增公开求解行为测试，在小图恢复内部主动暂停 200 ms：原实现的编译类对照失败，修复后通过；原有调用者截止时间和取消传播测试也通过。这一证据只证明该计时缺口，不证明后述冷启动失败全部由此引起。

## 初轮完整检查的失败记录

首次强制检查在 `cpSatColdRefinementTest` 失败后停止。随后使用 `--continue` 完成其余任务：1519 项通过、5 项失败、13 项跳过，发行包校验通过。该轮编译产物包含容量和弹出注册修复，尚未包含小图计时修复。

| 用例 | 观察结果 |
| --- | --- |
| `CpSatPetriRefinementTest.repeatedFreshDeadlockAlternativesUseBoundedPreflightAndRemainExecutable` | 首次可行但意外标记预算耗尽；继续检查时 trial 0 返回非可行方案 |
| `ByproductReplaySafetyTest.competingRoutesBelowAWrapperStillProduceTheRequiredSiblingSideOutput` | 超过 1000 ms 断言 |
| `TerminalBatchRecoveryTest.publicMultiUnitRecoveryRefusalMarksCutoffAndPreservesTheCoreMissingWitness` | 预期消耗 1 单位搜索预算，实际为 0 |
| `WideOrdinaryAllocationTest.actualMatrixSizeAllowsWidthSixteenTwoInputAssignments` | 超过 3000 ms 断言 |
| `ReferenceCapabilityRunnerTest.refillStartsASecondPassWithAFreshDeadline` | 预期 `SUPPORTED`，实际 `ENGINE_TIMEOUT` |

后续原实现对照命令还出现过 `materialDagLargeGraphTest` 的 3000 ms 超时，导致其依赖的目标测试没有执行；因此另行跳过这两个依赖任务运行单项原实现对照，确认该单项确实失败。没有将跳过的目标测试算作已执行。

小图计时修复后，调度停顿用例通过，但原有冷启动用例仍出现预算标记断言失败。不能把这些失败统一解释为主机争用，也不能通过隔离测试通过来覆盖完整套件的失败。

## 压力检查结果

修复后的代码顺序运行了 27 个独立 JVM，全部完成，进程状态、场景数和各场景正确性断言均通过。执行期间 `src/main` 和 `src/test` 的内容指纹保持一致。原实现对照另行执行，其失败属于新增回归测试的预期结果，不计入这 27 次。

| 范围 | JVM 数 | 覆盖与结果 |
| --- | ---: | --- |
| 普通规划、旁产物与证书 | 3 | 10 个场景，每个 12 次测量，通过 |
| V2 / CP-SAT 库存分配 | 2 | 合计 2952 个案例，通过 |
| 批量派发与回滚 | 3 | 60000 个库存案例、6000 个提供器案例，通过 |
| 通道分配 | 9 | 8 种拓扑各 12 次测量、20000 个随机对拍，通过；最大场景为 20 万设备 |
| 长数量存储 | 1 | long 与 150 位大整数各 120000 次测量，保存、重载、压缩、重插通过 |
| 循环规划 | 1 | 12 个场景、144 次测量、36 次预热，另含 72 次测量补料重规划与 18 次预热补料重规划，正确性检查通过 |
| 并发规划 | 1 | 4 个工作线程，1600 次测量查询、320 次预热，无错误或未完成查询 |
| 隔离边界回归 | 6 | 6 项通过 |
| 外部规划边界 | 1 | 38 项通过 |

循环规划允许按既有契约返回 `CUTOFF`：每个返回方案仍检查可执行性，缺料方案还检查补料后能重新规划。这些结果不证明全局最优，也没有清除预算耗尽标记。上述压力通过不能覆盖完整 JUnit 套件已经出现的失败。

压力夹具沿用本地既有审计工具；实际数量与结果已保存在配套 JSON 中。耗时来自同机此次运行，不作为整合包 TPS 或跨版本性能比较。

## 实际 Forge 回归

| 测试入口 | 结果 |
| --- | --- |
| 仓库内隔离兼容测试，包含新增弹出持久化用例 | 7/7 通过 |
| 原有世界生命周期夹具，`thunderbolt.gtlCompat=auto` | 11/11 通过 |
| 原有世界生命周期夹具，`thunderbolt.gtlCompat=always` | 11/11 通过 |

合计 29 次执行，包含 18 个不同测试方法。生命周期覆盖真实组装器提交与完成、两/三路配方、请求器精确回传及余量、缺料预览不扣库存、已有库存减少执行次数、实际 Mixin 数据包往返，以及弹出注册关闭与保存。

另有发布契约 Python 回归 12/12 通过；AWR 适配器回归 3 项通过、1 项因缺少外部 awrlib 跳过。与本地 GTLCore 参考源码进行访问器扫描，未发现可确定的生成方法重名冲突。

## 最终复查与仍未解决的问题

| JUnit 任务 | 通过 | 跳过 | 失败 | 最后一次命令中的状态 |
| --- | ---: | ---: | ---: | --- |
| `test` | 1465 | 13 | 0 | 执行 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 | 输入未变，复用通过报告 |
| `materialDagLargeGraphTest` | 1 | 0 | 0 | 输入未变，复用通过报告 |
| `cpSatColdRefinementTest` | 1 | 0 | 0 | 执行 |
| `materialDagColdReplayTest` | 20 | 0 | 0 | 执行 |
| 合计 | 1525 | 13 | 0 | 1538 项 |

13 项跳过分别为缺少新版 NeoECO 协议制品的 12 项和缺少 SuperMatrix 制品的 1 项。JSON 中的 `junit_rounds` 是各时点任务 XML 报告快照；部分依赖任务使用 Gradle 的 `UP-TO-DATE` 报告，不能将多次快照数量累加成新执行次数。

初轮完整检查列出的 5 项失败，在最终完整检查报告中均通过。小图计时修复后的定向检查曾仍有 1 项冷启动失败，最终冷启动任务又通过，因此这里只能记录为间歇性验证失败，尚未证明已消除。没有扩大预算、删除断言或清除原生部分解的预算耗尽标记。

配套 [结构化结果](benchmarks/gtl-full-audit-20261019.json) 同时保存了早期失败、真实执行的原实现对照、最终任务状态和压力结果。`final_validation_passed=true` 只表示最终检查通过；`all_final_code_validation_passed=false` 表示修复后代码的历史检查并非次次通过。

## 发行 JAR 校验

最终文件为 `build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，大小 5401695 字节，SHA-256：

```text
388285ecaf1e964378b9d34371dd5f879647bde77015b7bc01f17fe306182a37
```

主包 523 个类均为 Java 17 字节码（major 61），内嵌依赖未超过 Java 17。Mixin 配置与普通源码逐字节一致，refmap 与生成结果一致。包内未发现 GameTest、兼容夹具或 `runtime_empty.nbt`；`verifyReleaseJar` 通过。

## 验证命令

```powershell
# 首次强制检查，失败记录已保留
.\gradlew.bat check verifyReleaseJar --rerun-tasks --offline --max-workers=1 `
  --system-prop net.minecraftforge.gradle.check.certs=false --console=plain

# 最终完整复查
.\gradlew.bat check verifyReleaseJar --continue --offline --max-workers=1 `
  --system-prop net.minecraftforge.gradle.check.certs=false --console=plain

.\gradlew.bat -I tools/gtl-compatibility-game-tests.gradle runGameTestServer `
  --offline --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false --console=plain
```

发布契约与 AWR Python 回归、GTL 访问器重名扫描，以及规划、派发、通道、存储和并发压力测试另行执行。

## 验证边界

有限测试无法证明不存在所有 Bug。隔离 Forge 测试使用实际 AE2 和 Crafting Tree，并以最小夹具模拟 GTL 长数量入口及第三方摘要工厂；完整 GTLCore/AE2LT 整合包、客户端渲染和真实整包 TPS 未验证。可选协议缺少对应制品时按原条件跳过，不计为通过。
