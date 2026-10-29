# NeoECO 1.20.1-GTO 兼容与实测（2026-10-26）

按本轮用户要求，支持指定仓库的 GTO 版 NeoECO；此前移除普通 NeoECO 的记录保留为历史记录，不代表恢复旧版全部兼容。

## 目标与版本组合

- 仓库：[Yang120231/NeoECOAEExtension，1.20.1-GTO](https://github.com/Yang120231/NeoECOAEExtension/tree/1.20.1-GTO)。
- 核对提交：`21faad48f110ed682345215f3c3488d348da2b5a`。
- 实测原件：官方发行 `neoecoae-20.5.0-gto.1.jar`，与发行附件 `SHA256SUMS.txt` 一致。
- Minecraft 1.20.1、Forge 47.4.20、Java 21。

| 组合 | GTOCore | AE2-GTO | NeoECO | 说明 |
| --- | --- | --- | --- | --- |
| 原有基线 | 26.10.5 | 15.2610.8 | 不安装 | 保留旧环境支持；本轮另做实际服务器回归 |
| 本轮 NeoECO GTO 基线 | 26.10.9 | 15.2610.31 | 20.5.0-gto.1 | 三者同时升级，客户端和服务端使用相同 TB 构建 |

两个基线中的 Core 和 AE2 不可交叉混装。依赖声明仅放行表中两个已验证版本，不放开到任意后续版本。编译仍以旧基线 AE2 15.2610.8 为准。

新实例其余依赖按 GTO 官方 `0.6.0-dev-23` 服务器归档中的 packwiz 清单对齐，包括 GTM 26.10.70、GTOUtils 26.10.3、GTORenders 26.10.2、DataSyncLib 26.10.11、EMI GTO 1.1.25、LDLib 1.0.52.a、ExtendedAE 1.4.21。归档自带的 GTOCore 26.10.10 未用于本轮验收，实际换用上表的 26.10.9。本轮不宣称 26.10.10 已验证。

**旧 AE2LT 2.1.2-gto.1 与 AppliedE 0.14.3-gto 的依赖声明拒绝新基线。** 新版实例移除了这两个旧组件及不在该清单内的额外附属，没有改写它们的 JAR 或伪造版本。旧基线回归仍使用已有组件。新基线测试不代表新版 LT 调度器互操作已通过。

## 实现

### 批量与任务归属

`NeoEcoGtoBatchAdapter` 通过可选反射桥调用发行版公开的 `ECOFastPathFacade.prepareAllocated`，不打包 NeoECO 实现、不修改其发行 JAR。

- NeoECO 大型工作站自带 `IBatchCraftingProvider` 时优先使用原生接口；桥仅处理公开 fast-path 提供者。
- CPU 抽取材料前检查实际接收容量；因能量或提供者分摊缩小最终数量时，重新准备对应数量。
- 复制输入与任务 UUID，不保留借用的 `BatchJobView`。没有任务上下文或 UUID 时拒绝接管。
- 材料与能耗由 CPU 结算；NeoECO allocated 协议使用其私有账本和零能耗预留，避免二次扣料、扣能。
- 普通异常按公开协议的回滚保证视为拒收；接收结果不确定的异常传给现有停止派发路径，禁止退款后重投。
- 不支持批量时仅尝试一次携带 UUID 的普通推送；不恢复此前普通 NeoECO 的离线迁移、注册或配方兼容。

### 实测发现的启动问题

1. GTOCore 26.10.9 混淆重命名了后台执行器及方法。数据初始化转换器在 `Data.init()V` 内核对唯一的 GTOLib `(Runnable)V` 静态调用，继续跟踪原有任务，保留原执行器与服务端同步路径。
2. NeoECO 20.5.0-gto.1 的 `ECORemoteMachineUI$1` 在专用服务端初始化共享 UI 工厂时，JVM 验证会解析同步读取方法中的 `ClientLevel`。转换器将两处客户端世界读取隔离到客户端 helper，方法调用返回公共 `Level`；不替换其余 UI 逻辑。已经没有该访问的上游实现无需修改，出现意外部分匹配则在变更前失败。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| Gradle 完整检查、GameTest 源码编译、发布 JAR 校验 | 成功 |
| 自动测试 | 共 1561：1560 通过，1 跳过，0 失败、0 错误 |
| 新基线专用服务器，真实 NeoECO 发行 API | 11/11，通过后 `stop`，JVM 退出码 0 |
| 旧基线不安装 NeoECO 的实际服务器回归 | 47/47，JVM 退出码 0 |
| 新基线客户端，Mesa 软件 OpenGL、真实请求者验收 | 工作站加工、64 份 CPU 交付、规划器共存、两次热重载、关闭检查通过；JVM 退出码 0 |

唯一跳过项为已有 EAEP 测试：测试环境没有实际 `ScaledMolecularAssemblerPattern`。常规测试类路径不安装 NeoECO，另有可选依赖缺失测试确认桥安全关闭。

11 项协议验收使用真实 NeoECO facade 和真实 AE2/TB 执行器、库存与能耗结算，受控提供者实现发行版接口。覆盖原生能力优先、容量预检、材料/产物/能耗守恒、能量缩量、拒收/普通异常回滚、不确定接收停止重投、UUID 普通回退、快照和单次使用、`Long.MAX_VALUE` 边界、缺失上下文拒绝、忙碌恢复。它们不等同于组装实体 F9 机器的全部玩法验收。

旧基线 47 项回归另覆盖实体 GTO CPU、原有 AppliedE 流程、网络去重、I/O、矩阵重载、过滤、流体/闪电及 NBT 保存等；其结果仅适用于旧基线。

客户端已验证：服务端与客户端各识别 144 条 ECO 配方，EMI 显示单体 31 条、大型工作站 34 条；实际构建单体与成型大型工作站，GTO 样板修改器将输出从 1 放大到 4，输入被接收并分别产出 1 与 4。普通 GTO 规划和显式 ECO 规划均生成 64 份目标订单，并保持各自规划归属。

实体原生 GTO CPU 的 64 份订单通过真实 `ICraftingRequester` 交付；请求者收到 64 份，计入先前大型工作站的 4 份后总计 68，收到 `done=true,canceled=false` 的完成通知。不是以“提交成功”代替交付验证。

原始上游验收脚本在 `submitJob` 传入 `null` 请求者，再对独立链接断言 `isDone()`。本轮实际发现该版本 AE2 的 `CraftingLink.markDone()` 仅通知关联 nexus；独立订单没有 nexus，因此 CPU 清除已完成任务后，原脚本持有的独立链接仍返回未完成。失败证据保留在 `client-result-acceptance-final.json`、`client-result-acceptance-diagnostics.json`；后者记录了工作站正常完成、CPU 等待项归零及任务清除。

最终复验的独立 QA JAR 仅将上游 `verify` 内的提交接到 `NeoEcoGtoRequesterProbe`，创建同一真实网格上的请求者；保留上游 64 份订单、UUID、最终数量、完成状态与后续热重载断言，没有设置链接完成标记或模拟产物。该测试转换器位于 `src/gameTest/resources/neoecoqa/`，不进入生产包；NeoECO 与 AE2 原件保持原 SHA-256。

本机默认图形路径在 GTORenders 26.10.2 的 `GpuMotes.pointPool` / `GL33.glVertexAttribDivisor` 发生原生崩溃。客户端复验采用仅对该进程生效的 Mesa 26.2.4 llvmpipe 软件 OpenGL 及预加载启动器；不安装系统驱动、不修改 GTORenders，不据此宣称默认硬件图形路径已修复。一次启用全局 audit 的尝试另发生 `LegacyRandomSource` 并发访问崩溃，常规启动复验未重现，未将其认定为已修复。

最终热重载验证了添加配方同时更新服务端、客户端及两个 EMI 工作站分类，禁用数据包后移除旧配方。五张奖励袋战利品表、GTO 异步数据任务完成、前台与后台修饰键行为、关闭后的按键查询均通过；正常保存退出，JVM 退出码 0。结果见 `client-result-acceptance-requester.json`（`passed=true`、`shutdownKeysPassed=true`、缺失标记为空）。

## 验证边界

- 没有验证升级后的 AE2LT 时间轮调度器与 NeoECO 的联动，也没有验证所有可选附属及全部 NeoECO GUI、F9 功能、取消场景或长期压力负载。
- NeoECO 的 LDLib 槽编辑器注解在专用服务器扫描时仍产生客户端 `LocalPlayer` 相关的非致命告警；本轮服务器能够正常启动、完成 11 项验收并退出。
- 专用服务器不启用会强制加载第三方客户端目标的全局 Mixin audit；客户端 audit 尝试也包含 `ClassAlreadyLoadedException`，最终功能验收采用常规启动。不可将其写成“全局 audit 全通过”。

## 产物与证据

本轮已实测生产 JAR SHA-256：

```text
a440bc42f3182f58286eec7ed52a2025a97e58737e79efa0430598f7572e928b
```

依赖原件 SHA-256：

| 依赖 | SHA-256 |
| --- | --- |
| NeoECO 20.5.0-gto.1 | `24f60175359c65d688f24ec65feb2316ced73800486127f77bcec97d392b77b4` |
| AE2-GTO 15.2610.31 | `f63917bf5a2ca0692c0550ad2d045991f7a493d89021bfb30b51026d4ad31a3d` |
| GTOCore 26.10.9 | `6d06c4ec2b4cb64e257e9c526841b790ddeae247c37c269e1a39efd0e6dae850` |

本地构建证据位于忽略目录 `build/review/neoeco-gto-20261026/`：`full-check-release.log`、`unit-summary-release.json`、`server-result-protocol-final.json`、`mod-hashes-protocol-final.json` 与各次客户端结果。旧基线证据位于 `build/review/upstream120-20261022/runtime/runtime-results-neoeco-absence-20261026.json`。原始开发环境日志和下载的第三方 JAR 不纳入源码提交。

验收夹具只进入独立的 `neoeco-gto-probe.jar`，生产 JAR 不包含 `neoecoqa` 类或自动验收入口。

交付文件：`build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`，使用已测试原件回填，SHA-256 为本节所列值。按工作区约定，将当前累计完成且已验证的 GTO 移植与本轮适配汇总至本地 `1.20.1_GTL` 分支；不推送开发分支或标签，`AGENTS.md` 保持原样。
