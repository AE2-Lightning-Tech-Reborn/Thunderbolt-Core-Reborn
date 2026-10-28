# GTO 上游修复移植（2026-10-20）

> 后续更新：通道换算率缓存修复已移植，40 项存储回归通过，实际无限盘用例通过；最新交付包和未解决项见 [换算率移植实测](GTO_UNIT_RELOAD_PORT_20261020.zh-CN.md)。以下保留此前阶段的结果。

后续全面测试发现的规划回退修复、计时适配和当前未通过项见 [GTO_FULL_TEST_20261020.zh-CN.md](GTO_FULL_TEST_20261020.zh-CN.md)。以下为前一轮移植时的历史结果。

最新联网对比见 [GTO_UPSTREAM_COMPARISON_20261020.zh-CN.md](GTO_UPSTREAM_COMPARISON_20261020.zh-CN.md)。上游 GTL 已新增 `fdd766c`，将无限盘优化适配到 Forge 1.20.1，并包含通道换算率缓存失效修复；下文“仅有 1.21.1 性能分支”的范围说明为历史状态。

本轮从 Thunderbolt 上游 `1.20.1_GTL` 选择适用于当前 GTO 项目的公共修复，保留现有 GTO 26.10.5 / AE2 15.2610.8 及附属迁移实现。

## 已移植

- [`fd3db3f`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/fd3db3f0bebb904dd4318cad6c214435dc5ac5b6) 的容量核算：允许使用已占用字节的剩余单位；容量跨 63 位时仍检查类型上限；按实际借位计算剩余容量；数量和类型元数据共同溢出时完整处理进借位。
- 同一提交的输出端注销：只移除准确匹配的持久化记录；重复注册保留至最后一个句柄关闭；显式注销后的旧句柄不能删除替代注册。保留当前项目的 `persist` 参数约定。
- [`a305f6f`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/a305f6fbe8f118dc780e0d215dc53d9536fbda0e) 的公共摘要兼容：仅替换原界面的数量行，保留原方法和其他附属提示；已有合成树不重复覆盖；原生摘要在构造处适配计划，第三方摘要工厂保留原计划类型，并在返回后补充合成树。
- 合成树 Mixin 仅在 `ae2ct` 存在时启用。

## GTO 差异与未移植范围

- GTO 的 `CraftConfirmMenu.planJob` 本身接收 `long`，当前跟踪包装已覆盖该入口；不添加 GTL 专属的 `gtlcore$planLongAmountJob`。
- `GtlCraftingPlanSummaryBridge` 调用的是 GTL 可选接口，当前 GTO 没有该接口，不引入无效依赖。
- `fd3db3f` 中的求解器修复在当轮未移植，未整文件覆盖 GTO 算法；后续全面测试已按当前预搜索路径适配 witness 计时并修复 UNKNOWN 回退，详见本轮全面测试报告。
- 无限盘性能分支 `12dc97f` 基于 Minecraft 1.21.1 / NeoForge 21.1.219，其库存写入使用 `DataComponents.CUSTOM_DATA`，并修改保存快照所有权及存储布局；需要独立适配 1.20.1 的 NBT 和现有大数量存储，本轮未合入。
- ExtendedAE Plus 的 GTO 分支仍不能提供当前 AE2 的完整兼容；本轮不生成其迁移包。相关状态继续见 [GTO_ADDON_MIGRATION.zh-CN.md](GTO_ADDON_MIGRATION.zh-CN.md)。

## 回归验证

新增独立 BigInteger 容量公式对照、容量边界、准确删除持久化记录、第三方摘要保留和数量提示组合测试。移入三个真实 Forge GameTest，覆盖同面多主机、重复句柄、旧句柄替代注册；测试源码位于 `src/gameTest`，复用 `runtime_empty` 模板。

迁移工具测试改为读取已有 `thunderbolt.optionalModShapeDir` 属性，支持把构建目录迁出工作区。首次全量检查的两项失败来自原测试写死目录；首次服务端测试缺少自定义测试夹具的世界模板。原失败日志保留，补齐后重新验证。

运行服务端、客户端期间的一次全量检查另有两项既有计时测试失败。两项隔离补跑均通过，随后串行完整 `check` 通过，未修改其断言或时间限制。

| 最终验证 | 结果 |
| --- | --- |
| 完整 `check` 的主测试套件 | 1501 项：1488 通过、13 项可选协议跳过、0 失败 |
| 冷启动测试套件 | 23 通过、0 失败 |
| `compileGameTestJava` / `verifyReleaseJar` | 通过 |
| 隔离 Forge 基础 / 精确数量 / 批量套件 | 15/15、18/18、9/9，均正常退出 |
| 客户端进入世界、数量格式与真实表格提示 | 通过，退出码 0 |

客户端探针实际调用变换后的 `CraftConfirmTableRenderer`，检查 151 位存储数量的说明和完整提示。进入世界及探针通过，退出码为 0。退出阶段仍有 EMI/JEI 异步配方注册的空运行时异常与 ModernUI dead-thread 异常；本轮未修改这些第三方的退出流程，不将其计为已修复。

发布包为 `build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`，SHA-256：`69de6fd0618d9634b88cc9b88548fbcd9c4adb229e075137886a2785b2c1d108`。交付的是实机测试使用的副本，与最终校验构建的全部 JAR 条目内容相同；包内不含测试探针。

本轮构建、单元测试和隔离实例证据保存在 `D:/codex-builds/thunderbolt-upstream-port-20261020`。最终执行结果见该目录的 `results.json`。
