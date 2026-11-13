# 天枢 JEI 第一次审查返工

本轮修复审查的 P1（成功后返回失败可能重复派单）和 P2（旧原生数量/确认动作可能作用于复用 container ID 的新菜单）。冻结契约及现有公共 API 不变；新类型、动作信封和状态均为 Core 私有实现。未修改 AE2LT 或 AGENTS.md。

## 提交结果及重试

`CraftConfirmMenu.startJob` 中实际调用 `ICraftingSubmitResult.successful()` 时，Core 在原生返回菜单回调之前记录结果。成功永久消费本次绑定，即使回调不更换菜单、opener 返回 false、原生 `result` 仍非空，也不能再次派单。失败结果明确为 unsuccessful 时，才允许用户用一个新的有效动作重试。异常及没有观察到结果的调用不重新放行；不自动重试、退款或推断成功。

成功后的原生返回仍可以使用原始 locator 或已核实的等价 locator。消费状态限制的是后续动作/提交，不通过立即破坏来源绑定阻断正在执行的原生返回。

## 后续动作所属请求

服务端为已接受的 JEI 请求生成私有请求 UUID，随绑定传入数量及确认菜单；每个菜单另有服务端生成的会话 UUID。两者通过现有 GuiSync 同步。后续动作携带 container ID、菜单 UUID、请求 UUID 和递增序号，服务端先验证当前菜单、来源/网络及这些字段，再派发原生动作。旧包、重复序号和未附信封的原生包不执行，也不使新的合法菜单失效。

- `AEBaseMenu.sendClientAction` 的原生 `GuiDataSyncPacket` 客户端动作携带信封；服务端在 `receiveClientAction` 派发前验证。覆盖最终 startJob、GTL 长数量确认以及这些菜单中的其他原生动作。
- AE2 原生 `ConfirmAutoCraftPacket` 增加同一私有信封，并在实际 serverPacketData 前验证。
- 可选 AE2LT 大数量包适配在本仓 `mixin/compat/ae2lt/`，复用原有编码、数量限制及原服务端游戏线程回调，不增加硬依赖。
- 绑定数量确认只允许从该菜单已验证的动作派发作用域进入。普通未绑定菜单保持原生路径。服务端原生 autoStart 继续由已验证的数量请求设置，实际提交仍检查绑定和消费状态。

Core 内部 `terminal_order` 通道协议升为 2，拒绝与旧版 Core 混用；客户端及服务端须使用同一新构件。没有新增双仓公共协议或 API。

## 回归范围与构件基线

新增 Forge 行为回归覆盖：成功后回调不换菜单及 opener=false、明确失败后新动作重试及同动作重放、异常/未知结果、同 container ID 不同菜单 UUID/请求/来源网络的真实 AE2 数量包与 GuiDataSyncPacket 重放、当前动作正向派发、普通未绑定终端。成功消费回归由产品自动记录结果，不手动 retireOrder 代替。

隔离 PR 源码基于原汇总提交 `6fd6967`；只叠加本轮返工。当前主工作区的后续规划器提交 `1ed2c37` 和已有 `gradle.properties` 改动保留原样，不混入 PR 或本次固定联调构件。主工作区也完成相关 check/build，作为两组本地修改能共存的单独证据。固定构件、精确源码哈希、最新实际测试结果及未执行项以同名 artifacts JSON 为准；旧 v1/v2 固定字节和原失败证据不覆盖。

## 本轮结果

- Java 17、Gradle 8.14：隔离源码 compile/check/build/verifyReleaseJar 通过；完整 JUnit **1743 项，1741 通过、2 项既有可选跳过、0 失败/错误**。固定 GTLCore ABI 测试提供了 fix3 的目标 JAR，没有把缺少目标的假设跳过计为通过。无关的原有未跟踪测试未纳入这个数量。
- Forge 47.1.3 开发 GameTest **26/26**，包含原有 20 项和新增 6 项；在 AE2LT、WTLib、GTLCore、JEI 均未安装的测试服务端完成加载与行为回归。默认 CI 与 Radium 的 thunderbolt_nbt 断言同步为 26，独立兼容夹具的 14/5 项要求保持原样。
- 正式重混淆 JAR 在 Minecraft 1.20.1 / Forge 47.4.16 / Java 17.0.20，AE2 15.4.10、AE2LT 2.1.3-beta1、WTLib 15.3.3-forge、GTLCore 1.2.3.3-fix3、JEI 15.49.0.191、Inventory Tweaks 1.2.2 的原中键组合下，重新完成两类独立终端、各单模块 WUT、双模块及切换 **6/6**。实际经过 MouseHandler、Forge/JEI、数量页、确认页和原生 CPU/分子装配室；6 次 Core 发送、0 次旧发送、6 次取消，64 原木/0 木板变为 **58 原木/24 木板**，正常保存退出。
- 同一正式客户端中额外对真实 AE2LT `ConfirmBigAmountPacket` 编码/解码，并通过使用真实服务端连接的原游戏线程回调投递旧会话包；当前有效数量菜单未被确认或替换。它是大数量包适配的定向回归，**不宣称完成超大数量实体合成**。
- 发行包、Maven 包与实际客户端 JAR 字节相同；50 个公共 API class、嵌套依赖、许可证与旧 v2/冻结契约一致，生成 refmap 与发行包核对通过。

原始构造器注入选点失败、隔离输出路径导致的两项 refmap 测试找不到文件，以及在路径联接建立前已经开始的那次重复检查，都保留为失败记录。构造器挂点改为包装原 configureWrite 调用；refmap 读取路径联到实际生成目录后，完整检查重跑通过，未放宽断言或更改产品逻辑来掩盖这些失败。

固定 v3 构件及消费参数位于 `build/tianshu-jei-review-r1/delivery/BUILD.md`，本地 Maven 位于同级 `maven/`。客户端输入仍为程序化注入、服务端为集成服务器；人工实体鼠标、独立客户端连接专服、Curios/多终端多网络、真实客户端异常/权限/重放矩阵、Shift/Ctrl/编辑作弊组合、超大数量实体合成、TimeWheel 生产，以及世界/终端取物、磁卡吸取、非空模块合并与保存重开仍未执行，不计通过。两项审查问题已修复并回归，等待⑥重新只读审查，不代表整项流程完成。
