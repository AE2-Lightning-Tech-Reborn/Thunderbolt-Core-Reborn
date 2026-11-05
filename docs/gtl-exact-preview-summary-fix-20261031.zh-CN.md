# GTL 精确预览合成次数修复

本轮处理 ae2lt 的 Core 2.0.4 验收交回项：V2 对 `Long.MAX_VALUE` 个石按钮返回完整精确预览，但 GTL 摘要的 `gtlcore$getCraftTimes()` 为 `0`。起点为 `1.20.1_GTL` 的 `c4d4b8b`；原始完整发行包 SHA256 为 `b63453d02b6acb41117eb5980b2d2e49e3c67b65189be19e4fcd04e5c6379cc4`。本轮只修改 Core 项目，原整合包、原验收结果与原冻结包保持不变。

**契约与原因。** 核对实际 GTLCore `1.2.3.2-fix1`（SHA256 `f2ae810818b73e09d4f10e209d7ce755dee5755e339bfcc43d675d33c27db59e`）的 `CraftingPlanSummaryCraftTimes.aggregateByOutput`、`CraftingPlanSummaryMixin` 和 `CraftingPlanSummaryEntryMixin` 字节码：每项产物的次数等于所有相关样板的正执行次数之和，不乘单次产物数量；条目通过 `long` 字段及现有 GTL 编解码同步。因此完整的一石头到一石按钮预览应显示 `Long.MAX_VALUE` 次。旧桥只读取 `plan.patternTimes()`，而精确预览为阻止执行有意清空此表，展示信息由此丢失。

**修复。** `ExactPlanPreview` 在合并同一真实样板的多条具体路线后，按物理产物汇总精确执行次数。`ExactPlanReports` 用弱身份关联保存不可变的服务器展示元数据；`GtlCraftingPlanSummaryBridge` 优先读取该元数据，在 GTL 的 `long` 展示边界饱和投影，普通计划仍使用原来的样板表。发射器提供的物料不增加样板次数。精确用料、产量与字节报告及其网络格式不变；预览仍为 `simulation=true`、`previewOnly=true`、执行表为空，不产生大数执行能力。

涉及文件：

- `src/main/java/com/moakiee/thunderbolt/core/crafting/planner/ExactPlanPreview.java`
- `src/main/java/com/moakiee/thunderbolt/ae2/crafting/ExactPlanReports.java`
- `src/main/java/com/moakiee/thunderbolt/ae2/crafting/GtlCraftingPlanSummaryBridge.java`
- `src/test/java/com/moakiee/thunderbolt/core/crafting/planner/ExactPlanPreviewTest.java`
- `src/test/java/com/moakiee/thunderbolt/ae2/crafting/GtlCraftingPlanSummaryBridgeTest.java`
- `src/compatibilityGameTest/java/com/moakiee/thunderbolt/gametest/CompatTests.java`
- 本报告。

**验证记录。** 原始过程材料保留在 `build/gtl-summary-fix-20261031/`，不加入提交。`contract-red-pattern` 在旧生产实现上运行最终的四组数量断言：超 int 的 1/64 单次产量两组通过，`Long.MAX_VALUE` 两组均因 GTL 次数为 `0` 失败；修复后的 `targeted-green` 为 37/37 通过。新增回归同时检查批次次数、共享输出、同一真实样板的多路线、超 long 精确计数、发射器分离、库存不变和不可执行边界。

带 GTL 字段注入夹具和真实 Crafting Tree `1.20.1-1.1.1` 的 GameTest 14/14 通过，包含从真实 V2 规划转换到摘要的 `Long.MAX_VALUE` 预览，正常保存退出。环境为 Java 17、Forge 47.4.20、AE2 15.4.10。这一组使用 GTL 接口夹具，生产 GTLCore 的验证另行记录。

早期测试夹具未实现动态代理的 `getPrimaryOutput()` 默认方法，出现无配方计划；已补齐并核对真实用料，再保留上述红绿对照。GameTest 首次命令中的 Forge 版本参数被 PowerShell 拆分，未进入测试；仅终止该次自己启动的 Gradle，给参数加引号后完整运行通过。所有早期结果保留，没有放宽断言或预算。

完整 `check assemble verifyReleaseJar` 在 Java 17、默认 Forge 47.1.3、AE2 15.4.10 下通过，JUnit 共 1729 项：1727 通过、0 失败、2 跳过。跳过项为真实 Useless 新公开 API，以及本次命令未提供可选 EAEP 测试 JAR 的真实缩放样板用例；没有把缺少制品的用例计为通过。发行包审计通过：Java 17 字节码、无重复条目、无测试夹具、Mixin 配置和 refmap 匹配、许可证和 MixinExtras 嵌套依赖完整。

**生产 GTL 复现。** 从 ae2lt 指定交接目录读取原生产依赖、默认配置、种子世界及已重混淆的原探针，在本项目 `build` 中创建新世界副本，仅替换 Core。Java 17.0.20、Forge 47.4.16、AE2 15.4.10、GTLCore 1.2.3.2-fix1、AE2LT 2.1.3-beta1、默认 Radium；与原 `long-range-r8` 核对后，其余 88 个 JAR（含探针）、Radium 配置及种子 `level.dat` 的 SHA256 均相同。原探针 SHA256 为 `280d56ee38a258abe1b4009a1d4cd7b0fc3a7a5ff35e56041ffa396915ff3d3b`，本轮没有修改探针源码、字节码、断言或预算。

| 实际后端 | 请求石按钮数量 | GTL 摘要执行次数 | 预览专用 | 库存不变 |
| --- | ---: | ---: | --- | --- |
| AE2 原生 | 2147483652 | 2147483652 | 否 | 通过 |
| AE2 原生 | 9223372036854775807 | 9223372036854775807 | 否 | 通过 |
| V2 | 2147483652 | 2147483652 | 否 | 通过 |
| V2 | 9223372036854775807 | 9223372036854775807 | 是 | 通过 |

四组均通过。极值 V2 计划仍是 `simulation=true`、`previewOnly=true`、`incomplete=false`；石头 stored 和石按钮 crafting 均为 `9223372036854775807`，missing 为 `0`，精确 bytes 仍为 `27670116110564327437`。生产运行耗时 76.13 秒，正常保存并退出，exit=0。

本地原始证据为 `build/gtl-summary-fix-20261031/native-io-runs/core-summary-fixed-r1/` 下的 `native-io-result.json`、`process.json`、`console.log`；`summary.json` 保存检查汇总，`replay-provenance.json` 记录复现启动器的路径替换，`gtl-summary-bytecode.txt` 保存实际 GTL 契约依据。这些过程文件均不提交。

**交回复测制品。** 修复候选保留版本号 `2.0.4`，请用冻结路径和 SHA256 区分原包：

- 完整 JAR：[thunderbolt-forge-1.20.1-2.0.4.jar](../build/gtl-summary-fix-20261031/release/thunderbolt-forge-1.20.1-2.0.4.jar)，5470110 字节。
- SHA256：`efb0d63bc6fb2ab81f0778ab9f6792ec853468ddb61a1e90f6b0693b1639e06f`；同目录提供 `.jar.sha256`。
- 原始 `b63453...` 冻结包及外部整合包原文件保持不变。本轮没有推送。

ae2lt 可直接用本项目的隔离复现入口再次执行原四组断言，`label` 必须使用新名称：

```powershell
python -B -X utf8 'build/gtl-summary-fix-20261031/run-native-io.py' --direction long --role control --label ae2lt-summary-retest-r1
```

也可在自己负责的验收副本中替换上述完整 Core JAR；务必确认 `process.json` 中实际加载的 Core SHA256 为修复值，避免原 `pinned-core` 配置重新装回 `b63453...`。请回报原四组请求和 GTL 摘要、精确报告、库存不变、正常保存退出是否通过，并将本项结论更新到整包验收报告。

本项修复不改变整包其余验收状态：导出原预算仍有 `2/1200` 个 >50 ms tick（`0.1667% > 0.1%`）的历史失败；LDLib 异步保存异常未处理；有限供能、催化剂/膨胀样板、真实 EAEP 批量设备及新 Useless API 仍待验收。本轮没有重新执行这些场景，也未修改 NeoECO、AGENTS.md 或其他项目源码。
