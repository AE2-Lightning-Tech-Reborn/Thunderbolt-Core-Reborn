# GTL 1.20.1 边界测试隔离修复（2026-10-09）

修复了限时边界用例与其他测试共用 JVM 的执行方式：37 个导入案例和 1 个汇总检查改由 `externalPlannerBoundaryTest` 在独立 JVM 中运行，常规 `test` 必须依赖该任务，`build` 通过 `check -> test` 同样执行它。修复后完整构建通过：**1401 通过、0 失败、1 EAEP 跳过**。本轮仅修改 Gradle 测试任务，边界测试源码、原有 1 秒限制及生产预算均未改变。

被测基线为 `a06887e09d28f3ba096cad0d12ba76dc7bebf250`，修复文件用 JSON 中的 `build_gradle_sha256` 标识。环境为 Java 17 / Minecraft 1.20.1 / Forge 47.1.3 / AE2 15.4.10。

## 保留的首轮失败

[本轮此前全局压测](gtl-global-pressure-20261009.zh-CN.md)首次出现两个超时：`quantity/craftable-primary-no-variant-stock/1` 与 `quantity/fuzzy-leaf-white-stock/1` 均超过 1000 ms，随后汇总因只完成 35/37 个案例失败。原始 3 项失败及超时栈保留在历史报告与 JSON 中。

本轮在修改前重跑了带 JFR、GC 和 safepoint 记录的原共享 JVM 测试：1401 通过、0 失败、1 跳过，**未复现首轮超时**。记录中的最长 GC 暂停为 12.486 ms，最长 safepoint 为 41.998 ms；这份通过运行的记录无法解释历史失败，也不能据此宣称找到了其具体 GC、JIT 或调度根因。

修复明确消除了这些限时案例与此前 Minecraft/native 规划测试共享堆、原生状态和类加载历史的执行安排。该隔离措施不保证外部 CPU 调度永远满足 1 秒墙钟限制；没有把未复现的超时认定为已修复的生产算法缺陷。

## 验证

| 范围 | 结果 |
|---|---|
| 完整构建：常规 `test` | 1363 通过、0 失败、1 EAEP 跳过，共 1364 项 |
| 完整构建：独立边界任务 | 38/38 通过，含 37 个原案例与汇总检查 |
| 默认测试合计 | 1402 项，1401 通过、0 失败、1 跳过 |
| 测试清单审计 | 修复前后的 `(class, test name)` 多重集合完全一致；两分区互不重叠，无遗漏 |
| 独立 JVM 重复 | 10 个测试任务均复用实际边界任务的 classpath 和筛选；380/380 通过 |
| 默认 `test` 依赖 | `test --dry-run` 确认独立边界任务在常规测试之前，完整构建实际执行了两者 |
| 冷启动 | CP-SAT 1/1、物料 DAG 20/20 通过 |
| 发布检查 | 发布脚本 12/12，实际 2.0.0 JAR 的 refmap、入口与版本校验通过，POM 生成通过 |

### 本轮 JFR 统计

| 测试进程 | GC 暂停数 | 最大 GC 暂停（ms） | 最大 safepoint（ms） |
|---|---:|---:|---:|
| baseline-shared | 342 | 12.486 | 41.998 |
| fixed-test | 353 | 12.883 | 13.034 |
| fixed-externalPlannerBoundaryTest | 1 | 2.737 | 5.414 |

各进程执行范围不同：独立边界任务只执行 38 项，常规任务执行 1364 项，因此上表只用于核对隔离和暂停记录，不作为生产算法性能改进的结论。重型验证顺序执行，只设置本任务启动的 Java 优先级，没有停止其他用户进程。

## 复验与证据

在 Java 17 环境中运行：

```sh
./gradlew externalPlannerBoundaryTest
./gradlew test
./gradlew build
```

单独复验导入边界套件使用新任务 `externalPlannerBoundaryTest`；默认 `test` 和 `build` 保持全量覆盖。真实 EAEP 组件未提供，原跳过项仍不能算通过。生产与 Forge 运行时未修改，因此本轮没有重复已完成的模块压力与游戏测试；其范围和预算截断仍以历史全局报告为准。

实际发布 JAR：`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`，版本 2.0.0，5,364,076 字节，SHA-256 `2d9819c4716895d24c51e9f53d0bb1636ef2a4474b5c38aaeef34b4e808a0223`。本轮只验证本地产物，没有执行发布。

首轮失败索引、未改动基线、完整构建、380 次重复、测试清单审计、JFR 汇总、源码和产物哈希见 [gtl-boundary-timeout-fix-20261009.json](benchmarks/gtl-boundary-timeout-fix-20261009.json)。原始 XML、JFR 和环境日志保留在忽略目录 `build/gtl-boundary-timeout-fix-20261009`。
