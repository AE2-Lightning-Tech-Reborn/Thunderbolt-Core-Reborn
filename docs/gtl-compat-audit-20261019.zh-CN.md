# GTL 1.20.1 移植后兼容检查（2026-10-19）

检查基线：`1.20.1_GTL`，提交 `6c17e692c438cb7a3f3b09fc149e24af29a3e798`。

本文保留修复前的检查结果。后续修复与验证见 [兼容修复记录](gtl-compat-fixes-20261019.zh-CN.md)。

结论：定向自动检查通过，但仍有两类可从源码定位的 GTL 适配缺口，涉及长数量入口的算法状态跟踪、确认摘要的 GTL 扩展显示。另有一项 AE2LT 大数摘要组合风险需要运行验证。本次仅检查并记录，没有修改生产代码、提交或推送。

## 范围与证据强度

- 构建目标：Minecraft 1.20.1、Forge 47.1.3、AE2 15.4.10、Java 17。
- GTLCore 对照：本地 `GTLCore-ref` 源码快照，声明版本 `1.2.3.2-fix1`，没有 Git 元数据，不能据此证明与用户安装的二进制完全一致。
- AE2LT 对照：本地 `AE2-Lightning-Tech 1.20 GTL` 源码。
- 执行了定向 JUnit、附属模组二进制形状检查、发布包检查及源码注入重叠扫描；没有完成真实 GTL 整合包的组合启动、客户端确认界面及端到端合成验证。
- 以下缺口属于源码审查结果，不宣称已经在用户整合包中重现，也不能用通过的单元测试证明所有 Mixin 组合都能运行。

## 发现 1：GTL 长数量入口遗漏算法状态跟踪

Thunderbolt 的 `CraftConfirmMenuMixin` 只为 `planJob` 和 `data_energistics$planJob` 包装 `beginCraftingCalculation`，没有覆盖 GTLCore 新增的 `gtlcore$planLongAmountJob`。

对照 GTLCore 源码，长数量确认、重新算料、库存预留冲突后的重算都会直接调用该新增入口，再直接赋值 `job`。因此这些路径仍会经过 CraftingService 的规划器配置，但没有建立 `CraftingAlgorithmCalculationStatus` 的 requester/future 映射。

具体影响：

- 算法选中结果无法关联到新任务，算法名称可能为空，或者重算后保留上一任务的名称。
- 对没有精确预览标记的结果，确认界面可能回到 AE2 原生摘要，而不是 Thunderbolt 按算料快照生成的摘要。
- 从已跟踪的普通入口转入 GTL 重算时，旧 future 的 `forget` 也被绕过；状态表使用强引用，存在旧任务状态未清理的风险，尚未进行内存实测。

证据位置：

- `src/main/java/com/moakiee/thunderbolt/mixin/ae2/crafting/CraftConfirmMenuMixin.java:54`、`:62`、`:85`、`:104`。
- `src/main/java/com/moakiee/thunderbolt/core/crafting/algorithm/CraftingAlgorithmCalculationStatus.java:24`、`:45`、`:54`。
- GTLCore `mixin/ae2/gui/CraftConfirmMenuMixin.java:226`、`:244`、`:450`；`CraftAmountMenuMixin.java:78`。

建议修复方向：为 GTL 新增入口提供可选且可组合的任务跟踪与旧状态清理，并验证初次长数量确认、普通任务转重算、库存预留冲突重算三条路径。

## 发现 2：自建摘要和精确行显示绕过 GTL 扩展

有两处独立的衔接缺口：

1. 选中代理引擎后，Thunderbolt 在确认菜单里直接调用 `ThunderboltCraftingPlanSummary.fromPlan` 创建摘要，不再调用 `CraftingPlanSummary.fromJob`。GTLCore 只在后者内部填充每行的 `gtlcore$craftTimes`，因此该路径缺少 GTL 的合成次数元数据。
2. 对附带 `ExactPlanReport` 的行，`ExactCraftConfirmTableMixin` 在 `getEntryDescription` 的 HEAD 设置返回值并提前返回。GTLCore 在原方法 TAIL 添加的合成次数、实时库存占比和“库存已被占用”文字无法沿原方法路径执行。

影响范围是摘要扩展显示。没有证据表明这两处会直接破坏材料扣除或 CPU 执行。GTL 服务端的库存预留逻辑与显示钩子不同，不能由提示缺失推断服务端预留失效。

证据位置：

- `src/main/java/com/moakiee/thunderbolt/mixin/ae2/crafting/CraftConfirmMenuMixin.java:104`、`:114`。
- `src/main/java/com/moakiee/thunderbolt/ae2/crafting/ThunderboltCraftingPlanSummary.java:38`。
- `src/main/java/com/moakiee/thunderbolt/mixin/ae2/client/ExactCraftConfirmTableMixin.java:15`。
- GTLCore `mixin/ae2/crafting/CraftingPlanSummaryMixin.java:22`、`:26`；`mixin/ae2/gui/CraftConfirmTableRendererMixin.java:28`、`:48`、`:56`。

建议修复方向：为自建摘要补接 GTL 元数据，并让精确数量格式与 GTL 的附加提示共同生效；验证普通代理引擎摘要和精确数量摘要两种情况。

## 待组合验证：AE2LT 大数计划的专属摘要

`Ae2CraftingTreeCompatibilityMixin` 以优先级 2000 在 `CraftingPlanSummary.fromJob` 的 HEAD 改写计划参数。`CraftingPlanSummaryAdapter.adapt` 会把未知的 `ICraftingPlan` 实现转换成普通 `CraftingPlan`。

AE2LT 的 `BigCraftingPlanSummaryMixin` 在同一方法 HEAD 判断 `plan instanceof BigCraftingPlan`，依赖原始类型来调用携带精确大数的 `big.summary()`。如果通用适配先执行，该专属分支会失去原始类型，普通投影又不能保留专属大数摘要的全部信息。

本轮没有实际执行这两个 Mixin 的组合变换，故将其列为待验证风险，未计入上面两类已定位缺口。应使用当前 AE2LT 构建，验证超出 long 范围的大数计划在确认界面仍保留精确数量和全部产物行。

## 已通过的检查

### 定向回归与发布包

执行：

```powershell
.\gradlew.bat test `
  --tests 'com.moakiee.thunderbolt.compat.*' `
  --tests 'com.moakiee.thunderbolt.mixin.*' `
  --tests 'com.moakiee.thunderbolt.ae2.*' `
  --tests 'com.moakiee.thunderbolt.assets.*' `
  verifyReleaseJar --offline --max-workers=1 `
  --system-prop net.minecraftforge.gradle.check.certs=false --console=plain
```

本次实际执行结果：

| 任务 | 通过 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| `test` 定向用例 | 94 | 0 | 13 |
| `externalPlannerBoundaryTest` | 38 | 0 | 0 |
| 本轮合计 | 132 | 0 | 13 |

`materialDagLargeGraphTest` 为 UP-TO-DATE，不计入本轮通过数量。没有重跑完整规划器测试集。

检查覆盖 GTL 自动避让、调度选择、计划提交限制、可选 Mixin 选择、AE2 与附属模组接口形状、精确预览提交保护、合成规划钩子的组合契约及版本元数据。它们没有覆盖上述 GTL 新增入口和完整显示组合，因此与发现项并不矛盾。

发布包检查通过：refmap 与编译产物一致、Mixin 清单和 JarJar 元数据齐全。主包 521 个类均为 Java 17 字节码（major 61），递归检查的内嵌类不超过 major 61。

- 文件：`build/libs/thunderbolt-forge-1.20.1-2.0.0.jar`
- 大小：5,396,058 字节。
- SHA-256：`7b848dbb117b09afd5b21c6ec29810b86bbade86966aa49ab77ccf2b5d4b875d`。

### 可选模组实际二进制

形状测试使用 AdvancedAE `1.3.6-1.20.1`、AppliedE `0.14.3`、Crafting Tree 文件 `7182165`、ExtendedAE Plus Forge 版本 ID `pN9pMjiW`，均通过对应断言。

另外将实际 NeoECO `20.4.2` 与 ExtendedAE Plus Forge `1.5.5` 加入测试运行类路径，重跑接口回退和 SuperMatrix 桥接检查：4 项通过、0 项失败、1 项按接口可用性跳过。这是重复验证，不与上表相加计算独立用例数。

已核对 NeoECO 20.4.2 的 SHA-512 与发布元数据一致。其 JAR 与 20.3.0 都没有 `ECOBatchCapacityProvider`、`ECOBatchDispatchContext`、`ECOFastPathDispatchProvider` 或 `ECOFastPathFacade`；回退测试确认适配器返回不可用并保留普通派发。

ExtendedAE Plus Forge 1.5.5 没有 `ScaledMolecularAssemblerPattern` / SuperMatrix API，即使真实 JAR 在类路径里，相应测试仍会跳过。因此原本的 13 个跳过项应解释为：12 项需要另外提供 NeoECO 新协议产物，1 项需要提供 SuperMatrix API 的产物；不能据此认定当前公开 Forge 版本不兼容，也不能把这些接口宣称为已完成实测支持。

### 源码注入扫描

分别对 GTLCore 和 AE2LT 源码运行了 `tools/method_overlap.py`、`tools/accessor_collisions.py`。严格按目标类全名和生成方法名比较，未发现访问器生成方法重名。CPU 派发家族由 GTL 自动避让，`computePlan` 仍保留规划入口。

这类扫描只能辅助定位，不能证明没有运行时注入失败，也不能替代整合包内验证。
