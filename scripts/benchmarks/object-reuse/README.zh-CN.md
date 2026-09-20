# AE2 对象复用三方基准

测量同一个 `AEItemKey.of` 公共入口、NBT 复制和资源键操作。`ComparisonProbe` 另外输出语义观察，不会因为观察到语义差异自动失败；必须查看 `COMPARE_PROBE`，不能仅以 Gradle 成功推断语义正确。

- BASE：使用对象优化之前的 Thunderbolt 提交 `3798e5b0300eddb19706cd9352ba0a29f957048b`，AE2 对象实现保持原生；仍加载相同的 Thunderbolt 其余功能，不是完全移除 Thunderbolt 的整包。
- LEAN：同一个 BASE 环境额外加载 Lean jar。
- OURS：使用对象复用实验分支，不加载 Lean。

共同环境：Minecraft 1.21.1、NeoForge 21.1.219、AE2 19.2.17、Java 21。性能测量不加入七个附属，以免启动冲突影响公平性；附属联合验证单独记录。

在对应项目目录运行，路径变量换成实际位置：

```sh
comparison_harness=/path/to/Thunderbolt-Core-Reborn/scripts/benchmarks/object-reuse
comparison_lean_jar=/path/to/leanobject.jar
bash ./gradlew runComparisonGameTestServer \
  -PcomparisonMode=BASE \
  -PcomparisonHarnessDir="$comparison_harness" \
  -PcomparisonLeanJar="$comparison_lean_jar" \
  -PthunderboltEnableAe2DevRuntime=true \
  -I "$comparison_harness/comparison.init.gradle" --console=plain
```

LEAN/OURS 修改 `comparisonMode` 并选择正确的项目目录。普通工厂独立循环再加 `-PcomparisonDirectFactoryOnly=true`。BASE 和 LEAN 请使用隔离的原始基线目录；基准可能被注入旧项目的 main 测试宿主，不应发布那个目录生成的 jar。当前实验项目使用独立 gameTest 源集。

每个模式运行三个独立 JVM。独立循环每 JVM 8 轮预热、9 轮测量、每轮 100 万次；混合程序 6 轮预热、7 轮测量，工厂每轮 20 万次，NBT 每轮 5000 次。每 JVM 取中位数，再对三个 JVM 取中位数；保留各次结果与范围。结果进入 volatile sink，分配使用 ThreadMXBean 测量当前线程。大小代理必须单独运行，不能混入计时结果。

BASE/LEAN/OURS 轮换执行，避免并行基准互相争用 CPU。独立循环与混合分发的 JIT 上下文不同，不混用；首次并发等值对象检查与默认组件／编解码检查也分别列出。等值库存场景是 256 份已经建立的独立等值 NBT 的热查询，工作集跨轮重复，不含每次解码 NBT 的成本。注册物品轮换数量可能因测试 Item 不同而有差异，不用这一行作严格排名。

日志命名 `MODE-direct-N.log`、`MODE-mixed-N.log`（N 为 1–3），可用 `summarize.py LOG_DIRECTORY OUTPUT_JSON` 汇总。脚本检查每次实际 GameTest 成功，输出中位数、范围、原始每进程样本以及语义观察。它不自动把不同语义解释成漏洞或性能优势。

对象浅大小可单独用提供的只读 Instrumentation 代理测量：

```sh
measurement_output=/tmp/ae2-object-size-agent
mkdir -p "$measurement_output/classes"
javac -d "$measurement_output/classes" "$comparison_harness/measurement-agent/SizeAgent.java"
jar cfm "$measurement_output/size-agent.jar" "$comparison_harness/measurement-agent/MANIFEST.MF" \
  -C "$measurement_output/classes" .
```

在普通工厂独立循环的 Gradle 命令上再加 `-PcomparisonSizeAgent="$measurement_output/size-agent.jar" -I "$comparison_harness/size-agent.init.gradle"`。只记录 `DIRECT_KEY_SIZE`；该 JVM 的耗时不加入性能表。此大小不包含栈、组件和缓存节点，依赖压缩引用与对象对齐设置。

堆叠上限专项对照可加 `-PcomparisonStackLimitOnly=true`，跳过其他基准及语义探针。它测量普通物品、自定义上限物品、64 件输入的组件命中，以及新建等值组件栈；沿用相同公开工厂入口，输出 `STACK_LIMIT_BENCH`。每种场景 8 轮预热、9 轮测量、每轮 20 万次，记录 ns/次和线程分配 B/次。外部状态突变的 `dynamic_limit_after_change` 只是人工边界观察，不作为实际附属兼容故障判据。
