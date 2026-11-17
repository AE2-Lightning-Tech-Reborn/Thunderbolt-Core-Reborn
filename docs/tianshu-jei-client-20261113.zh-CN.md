# 天枢 JEI 客户端测试与 Core v2 修复

本轮完成 Core 侧真实客户端自动化测试，并修复三个实际问题。固定客户端组合的六个天枢场景通过；这不是③至⑨全部门禁通过声明。新的 v2 构件替代④的 v1 联调包，①契约、公共 API、①固定构件和④原固定字节均保持不变。

## 环境与证据边界

- 正式重混淆 Core JAR；Minecraft 1.20.1 / Forge **47.4.16** / Java **17.0.20**。Core 编译仍沿用本仓 Forge 47.1.3、Java 17。
- 使用③成员提供的 AE2LT `2.1.3-beta1` 运行 JAR、AE2 `15.4.10`、WTLib `15.3.3-forge`、GTLCore `1.2.3.3-fix3`、JEI `15.49.0.191`。最终环境保留 Inventory Tweaks `1.20.1-1.2.2`、SophisticatedCore 及原中键配置。
- 固定输入复制到 Core 本仓的隔离测试目录。剔除历史 `ae2lt-issue63-client.jar` 探针，替换旧 Core；重复 JECharacters 只启用 `4.6.11`，未启用 `4.6.3-fix`。完整实际模组哈希清单随构件交付，不能将其称为未改动的原整合包。没有启用 NeoECO。
- 本轮是真正 Forge 客户端和其集成服务器。通过 `MouseHandler` 注入移动、按下和释放，实际经过 Forge `ScreenEvent`、JEI 悬停、GTLCore 处理器、Core C2S 请求、数量页、确认页、原生确认包及真实 CPU。属于程序化输入，**不是人工实体鼠标验收，也不是独立客户端连接专服验收**。
- 测试探针仅设置场景、填充数量控件和观察发送/取消调用。没有替换 JEI ingredient、终端 host、网络、库存或提交服务，没有直接调用服务端提交。取消观察器调用原 `Event.setCanceled`，不改变取消语义。探针未打入 Core 发行包。
- 历史世界中的天枢多方块未成型，不能作为生产夹具。因此在副本中新建真实 AE2 网络：样板供应器、分子装配室、64K 元件与驱动器、64K CPU、创造能量元件及无线接入点，使用物理相邻连接，无虚拟 grid/host/service。本轮验证的是天枢终端向真实原生 CPU 下单，不冒充 TimeWheel 多方块验收。

## 修复

1. **客户端加载崩溃。** ④ v1 的兼容探针调用 `getClassNode(..., false)`；ModLauncher 不支持读取未转换字节码，实际启动抛出 `IllegalArgumentException`。现在从 Forge 已发现的 GTLCore mod 文件读取 class 资源并检查 ABI，避免触发转换器或链接可选客户端类。定向测试同时覆盖资源流解析。
2. **成功提交后的返回失败。** WTLib 的 `ItemWT.lambda$getMenuHost$0` 会用同一背包槽重新创建 locator。旧代码把 locator 对象不同当作来源变化，阻止原生返回。现在同时重新解析原始锚点与候选 locator，核对实际 ItemStack 对象、host 类型、玩家、供电/范围及网络；原始锚点仍必须有效，不能借新 locator 改用其他槽、物品或网络。Forge GameTest 加入重建 locator 的回归。
3. **Inventory Tweaks 组合抢占 JEI 中键。** 实际调用栈定位到 AE2LT `TianshuInventoryTweaksCompat.onMousePressed` 先手动分派并取消事件，导致 GTLCore 和 Core 均未发送请求。Core 新增客户端入口，借助对 AE2LT/Inventory Tweaks 的**可选 CLIENT BEFORE 顺序约束**，让支持的天枢上下文先执行已打补丁的原 GTLCore 处理器；仍使用其悬停、编辑/作弊模式过滤及一次发送/取消。处理后 Forge 跳过后面的订阅者；普通终端槽和 Shift/Ctrl 继续交还原输入链。没有改 AE2LT，也没有新增编译依赖、安装要求或运行时硬依赖。

## 客户端结果

最终固定组合包含 Inventory Tweaks；每场景数量为 4。

| 场景 | 结果 |
| --- | --- |
| 无线天枢合成终端（实际增强菜单/界面） | PASS |
| 无线天枢样板编码终端 | PASS |
| 仅含天枢合成模块的 WUT | PASS |
| 仅含天枢样板模块的 WUT | PASS |
| 双天枢模块 WUT，合成模块 | PASS |
| 同一双模块 WUT 切换至样板模块 | PASS |

六次实际中键对应 **6 条 Core 请求、0 条旧 GTLCore 请求、6 次中键事件及 6 次取消**。每次数量页/确认页的服务端绑定有效且指向原网络，规划数量为 4、非模拟。真实库存从 64 原木/0 木板变为 **58 原木/24 木板**，各次严格消耗 1 原木、产出 4 木板；客户端正常退出，所有维度正常保存。

此前停用 Inventory Tweaks 的同六个场景也通过，用于定位抢占；它们是对照重复执行，**不累计成十二种功能通过**。WUT 模块使用真实 WTLib 数据格式和原生合并 helper 构造，再由实际物品、host、菜单执行；没有通过合成台逐步制作 WUT，不能据此认定合并冲突、非空草稿/网格保留或磁卡保存测试通过。

最终 JUnit、Forge GameTest、发行包/refmap 检查、Maven 消费和逐文件 SHA256 结果以同名 `.artifacts.json` 为准。源码、完整发行 JAR 与实际客户端加载的 JAR 哈希绑定；最终包不包含 QA mod。全部原有公共 API class 和嵌套依赖字节与①固定包核对。

## 失败记录与剩余门禁

本轮保留初始启动脚本重复加入原版客户端导致 JPMS 包重复、测试探针包名错误、强制全局 Mixin 审计误触发其他模组缺失 EMI 类、历史多方块未成型、夹具接入点朝向/物品同步未完成等失败。它们分别修正或明确隔离，均不计通过；默认运行关闭可选的全局审计，实际核实本次 Core 挂点已经应用。v1 的真实加载崩溃、locator 返回失败及整理兼容抢占作为产品问题保留，不以最终通过抹去。

以下未执行，不计通过：独立客户端连接专服；人工实体按键；客户端 Curios、多终端/多网络、断电/失联/权限拒绝、迟到包/重复确认及 Shift/Ctrl/编辑作弊切换组合；客户端超大数量；普通非天枢终端专项回归；TimeWheel 实体生产；世界中键、终端库存中键、磁卡实际吸取；非空模块数据合并及保存重开。相关项目仍由③及⑤至⑧覆盖。

构件消费方式见 `build/tianshu-jei-client-20261113/delivery/BUILD.md`。不要再用④ v1 进行本次客户端联调。上述为客户端测试交付时的本地快照，随附 JSON 的 `committed` / `remotePublished` 字段记录该时点，不描述后续 PR 发布状态。未修改其他仓库或 AGENTS.md；⑥仍需对新增输入顺序、反射门控、locator 解析和客户端证据只读审查。

PR 整理仅额外更新默认 Forge CI 与 Radium 的 `thunderbolt_nbt` 回归断言，由 14 项改为 20 项（14 项原有测试加 6 项终端下单测试）；独立 `thunderbolt` 兼容夹具仍是 14 项，Radium 专用夹具仍是 5 项。运行时代码与已测试 v2 构件的逐文件源码哈希保持一致。JUnit 的 1742 项为当时本地工作区总数，包含两项原有未跟踪的 `TianshuReusableStockOracleTest`；该无关文件不随本 PR 提交，因此这些总数不作为干净检出或远端 CI 的测试数量声明。固定 GTLCore ABI 测试须显式提供目标 JAR，未提供时依其假设跳过。
