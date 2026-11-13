# 天枢终端交互契约 v1

契约标识：`tianshu-terminal-interaction-v1`。第①步交付，等待第②步只读审查；不是功能修复或联合验收通过声明。审查通过后，第③、④步按此契约独立开发；修改本文约定的类型、解析或交互语义，须退回①、②。

## 1. 固定基线与结论

| 项目 | 固定值 |
| --- | --- |
| Core 分支 / 源码 | `1.20.1_GTL` / `5926ab84eab951beb74480fbf4a17f7b0d3444b9` |
| AE2LT 分支 / 只读源码 | `1.20.1_GTL` / `6a7683bcf38841d53a9559cc2cf0f0551706340d` |
| Minecraft / 映射 / Java | `1.20.1` / official `1.20.1` / Java 17 |
| Core / AE2LT / AE2 / WTLib | `2.0.4` / `2.1.3-beta1` / `15.4.10` / `15.3.3-forge` |
| Core / AE2LT 编译用 Forge | `47.1.3` / `47.1.47`，分别沿用本仓配置 |
| 联合运行环境 | 由⑦记录实际双方构件、Forge、Java、JEI、Curios及整理模组版本；本步未启动游戏 |

结论：现有 AE2 菜单、locator、host 与 Core 合成 API 已能承载双方交互，**本步不新增 Java 共用接口、注册表、网络包或终端枚举**，也不改变现有二进制 API。下面的“上下文”“会话”“失败原因”是行为契约，不是要求双方各造一套同名 Java 类型。第④步内部实现和协议归 Core；第③步不依赖其内部类型。

制品、文档校验值、构建与测试结果见同目录的 `tianshu-terminal-interaction-v1.artifacts.json`。保留两仓既有工作；本步只写 Core 本仓，不提交、不推送，最终汇总提交留给全部门禁完成后。

## 2. 已核对的入口及证据边界

### 2.1 AE2LT（路径相对 AE2LT 根目录）

| 路径 | 当前入口 / 后续责任 |
| --- | --- |
| `src/main/java/com/moakiee/ae2lt/integration/ae2wtlib/Ae2wtlibIntegration.java` | `registerTerminal()` 已分别注册 `tianshu_crafting` 与 `tianshu_pattern_encoding`；`register()` 的磁卡升级目前只显式覆盖合成物品 |
| `src/main/java/com/moakiee/ae2lt/menu/TianshuWirelessCraftingTermMenu.java` | 合成无线菜单，继承 `TianshuCraftingTermMenu` → AE2 `CraftingTermMenu`；公开 `getNetworkNode()`、`getWirelessHost()` |
| `src/main/java/com/moakiee/ae2lt/menu/TianshuWirelessPatternEncodingTermMenu.java` | 样板无线菜单，继承 `TianshuPatternEncodingTermMenu` → AE2 `PatternEncodingTermMenu`；公开 `getNetworkNode()` |
| `src/main/java/com/moakiee/ae2lt/integration/ae2wtlib/TianshuWTMenuHost.java` | 样板无线 host，继承 WTLib `WTMenuHost`；`patternEncodingLogic` 持久化 |
| `src/main/java/com/moakiee/ae2lt/logic/tianshu/terminal/TianshuWirelessCraftingTermMenuHost.java` | 合成无线 host，继承 WTLib `WCTMenuHost`；原生合成网格及 `ae2lt:tianshu_workstations` 持久化 |
| `src/main/java/com/moakiee/ae2lt/integration/ae2wtlib/TianshuWctIntegration.java` | `hasTianshuCrafting()` 与 END 玩家 tick 只检查合成模块；实际吸取调用 `MagnetHandler.handle()` |
| `src/main/java/com/moakiee/ae2lt/mixin/ae2wtlib/CraftingTerminalHandlerMixin.java`、`WutCraftingPredicateMixin.java` | 现有世界工具增强的 `getLocator()` 调用范围与 `hasTerminal(..., "crafting")` 扩展 |
| `src/main/java/com/moakiee/ae2lt/integration/ae2wtlib/TianshuWirelessIngredientSource.java` | 另一条 JEI 材料供应入口；当前仅查样板模块，不能当作已覆盖两类终端的统一 resolver |
| `src/main/java/com/moakiee/ae2lt/integration/ae2wtlib/WirelessTerminalFrequencyLink.java`、`src/main/java/com/moakiee/ae2lt/mixin/ae2wtlib/WTMenuHostMixin.java` | 既有频率路由及 host 的 actionable node / range 适配；Core 必须复用 host 结果 |
| `src/main/java/com/moakiee/ae2lt/integration/ae2wtlib/TianshuTerminalMerge.java`、`src/main/java/com/moakiee/ae2lt/mixin/ae2wtlib/TianshuTerminalMergeMixin.java`、`TianshuTerminalRecipeMixin.java` | 已有合并和冲突拒绝；③负责扩充双模块覆盖与数据保留 |
| `src/main/java/com/moakiee/ae2lt/client/tianshu/TianshuMaintenanceTermScreen.java`、`TianshuCraftingTermScreen.java`、`TianshuPatternEncodingTermScreen.java`、`TianshuInventoryTweaksCompat.java` | Shift 中键维护、Ctrl 中键元件配置、样板数量编辑及整理模组抢占兼容；③统一维护 |
| `src/main/java/com/moakiee/ae2lt/mixin/big/BigCraftAmountMenuMixin.java`、`src/main/java/com/moakiee/ae2lt/network/tianshu/ConfirmBigAmountPacket.java` | 既有数量确认、当前 container 校验和 Core `CraftingRequestMenu` 调用；④复用，不能越仓修改 |

这些是源码/二进制检查结论，尚未运行故障复现，不能据此认定唯一根因。

### 2.2 JEI 入口归属

Thunderbolt Core 当前源码中没有独立 JEI 中键事件处理。其已提供的是共享合成规划/确认能力。

本仓旧测试目录中的 **GTLCore** `gtlcore-1.2.3.2-fix1.jar`（SHA256 `f2ae810818b73e09d4f10e209d7ce755dee5755e339bfcc43d675d33c27db59e`）包含：

- `org.gtlcore.gtlcore.client.forge.ForgeClientEventListener.onJeiWirelessOrderMousePressed(ScreenEvent.MouseButtonPressed.Pre)`：中键、JEI 物品悬停、编辑模式等检查；发送请求后取消事件。
- `org.gtlcore.gtlcore.integration.ae2.wireless.JeiWirelessTerminalOrderPackets.sendRequest(AEKey)`。
- `org.gtlcore.gtlcore.integration.ae2.wireless.WirelessTerminalCraftingMenuService.open(ServerPlayer, AEKey)`：重新扫描背包及 Curios，构造无线 host 后打开 `CraftAmountMenu`。

上述由该 JAR 的 `javap -p -c` 核对，**不是本次目标整合包的版本确认，也不是复现证据**。④先核对真实整合包承担“CORE JEI 中键”的模组、版本、哈希、事件优先级及请求链。任何 GTLCore 挂点只属于④的可选适配实现，不构成双方共用 API；不在本步引入 GTLCore 或 JEI 依赖。不按旧 JAR 强行确定运行时挂点。

## 3. 冻结的现有类型与签名

以 AE2 `15.4.10`、WTLib `15.3.3-forge` 的真实 JAR 及 Core 本仓源码为准，不使用 1.21.x 或 GTO 的签名。

```java
// appeng.menu.AEBaseMenu，双方已有依赖
MenuLocator getLocator();
Object getTarget();
Player getPlayer();
IActionSource getActionSource();
boolean isValidMenu();
boolean stillValid(Player player);

// appeng.menu.me.common.MEStorageMenu，两类菜单已有的共同父类
ITerminalHost getHost();
IGridNode getNetworkNode();
boolean isPowered();
void handleInteraction(long serial, InventoryAction action);

// appeng.api.storage.ITerminalHost extends ISubMenuHost
MEStorage getInventory();

// appeng.api.networking.security.IActionHost
IGridNode getActionableNode();

// appeng.menu.locator.MenuLocator
<T> T locate(Player player, Class<T> hostInterface);

// appeng.menu.me.crafting.CraftAmountMenu
static void open(ServerPlayer player, MenuLocator locator, AEKey key, int initialAmount);

// com.moakiee.thunderbolt.api.crafting.CraftingRequestMenu
boolean thunderbolt$planRequest(AEKey key, BigInteger amount, CalculationStrategy strategy);
boolean thunderbolt$isExactRequest();
Component thunderbolt$getRequestError();

// com.moakiee.thunderbolt.api.crafting.CraftingAmountMenu
void thunderbolt$setInitialAmount(BigInteger amount);
```

`CraftAmountMenu.open()` 返回 `void`，调用后必须检查实际 `player.containerMenu`，不能把“调用了 open”当作成功打开或成功下单。`CraftingRequestMenu` 是确认菜单入口，不是 JEI 点击即提交的接口；不得跳过数量和确认流程。

`CraftingAmountMenu` 是可选的数量扩展能力：调用前使用 `instanceof` 检查。AE2LT 的 `crafting/big/BigAmountMenu` 已继承该接口并转发初始数量；只装 Core、没有该扩展时保持原生数量菜单，不强制类型转换或新增对 AE2LT 的依赖。

WTLib 专属调用只留在③：`WUTHandler.hasTerminal(ItemStack, String)`、`getCurrentTerminal(ItemStack)`、`findTerminal(Player, String)`、`getItemStackFromLocator(Player, MenuLocator)`、`Platform.findTerminalFromAccessory(Player, String)`；`WTMenuHost.stillValid()`、`rangeCheck()` 和 `getActionableNode()`。Core 不需要 WTLib/Curios 的实现类或其 locator 子类。

## 4. 终端识别、定位和网络解析

### 4.1 种类、能力与物品身份

两类身份是 WTLib 注册键 `tianshu_crafting`、`tianshu_pattern_encoding`。③同时覆盖独立物品、分别仅含一个天枢模块的 WUT、双模块 WUT 及切换。不能仅用 `ItemWUT` 类型、物品显示名称、“存在原版 crafting 模块”或天枢合成实现类判定全部能力。

**已安装模块**与**当前菜单模块**分开处理：世界工具增强按 WTLib 既有选择规则找具备能力的物品；菜单操作以实际打开的菜单及 host 为准。双模块 WUT 的工具增强不能因两个模块各执行一遍，模块选择不能隐式改变另一模块的编辑数据。

### 4.2 有菜单：当前菜单是唯一来源

④的服务端入口从发送玩家的 `containerMenu` 获取当前 `MEStorageMenu`，校验玩家、container ID、当前菜单会话、`isValidMenu()`、`stillValid(player)` 和非空 `getLocator()`。不得从另一个玩家、客户端提交的 host/grid、默认手持物或第一次扫描命中的其他终端构造上下文。

网络从该菜单的 `getNetworkNode()` 和当前 host 的 `IActionHost.getActionableNode()` 获取；二者同时存在时必须指向同一 `IGrid`，否则拒绝。没有公开菜单 node 时，可从该 host 的 actionable node 解析，不能扫描别的终端兜底。不得用 `WirelessTerminalItem.getLinkedGrid()` 绕过 AE2LT 已有 host 路由。原始节点查询不额外引入通道或频率所有权策略；执行动作时仍须检查实际供电、host 的连接/范围和对应能力。

交互继续沿用该 host 的 `MEStorage` 与该菜单的 `IActionSource`。不能用 `IActionSource.empty()`、机器身份或另一网络的全局库存代替玩家来源。AE2 15.4.10 的 `IGrid` **没有**旧版 `getSecurityService()` / `SecurityPermissions` 接口；权限校验须遵循目标 AE2 与实际整合包的菜单、host、存储和提交入口，不杜撰旧版 API 或宣称原版不存在的权限场景已通过。

### 4.3 无菜单：真实 locator 与 WTLib 选择

③复用 WTLib 的 locator/终端选择逻辑，并扩充两类天枢能力。已核对 `WUTHandler.findTerminal()` 的原版优先级是可用的 accessory locator 优先，其后玩家库存槽位顺序。双天枢种类的能力扩展须作用在同一次选择中，不能“先全局找合成、失败再全局找样板”而改变物品优先级。WTLib 既有缓存失效规则随移动、切换、升级和登录等场景一起验证。

`MenuLocator` 定位的是 host，不保证能直接 `locate(player, ItemStack.class)`。③用 `WUTHandler.getItemStackFromLocator()` 取得真实物品；Curios 沿用库提供的 locator，不伪造背包槽位。Core 仅传递当前菜单的原生 locator，不解释其子类型，不序列化背包索引来代替 Curios locator。

选定来源后，在动作执行时重新核对物品仍在相同 locator、对象仍是同一来源且所需模块仍存在。移走/替换、模块切换、断电/失联、网络替换或访问被拒绝，使旧动作失败；不得在失败后改用另一终端或网络。频率路由行为由③沿用并验证现有适配，Core 不自行复制另一套路由规则。

### 4.4 数量页、确认页和网络生命周期

同一请求贯穿“来源菜单 → 数量菜单 → 确认菜单 → 返回”：保留原始 locator、来源物品/host 身份及网络绑定。打开子菜单会重新解析 locator，故**只复用 locator 仍不足以保证不换网**；在子菜单和最终提交阶段还须验证来源身份/网络未改变。发生切换时拒绝旧请求，重新在新来源发起，不悄悄继续。

④在 Core 自己的内部状态中保存、验证和清理上述绑定；不要求③导出 AE2LT 实现对象或修改 AE2LT 数量/确认文件。关闭、返回、登出、换维度/网络、请求完成及失效都清理状态；不能把服务端 grid/node 引用发给客户端或持久化到终端 NBT。

## 5. 输入、数量与重复触发

- 世界中键取物、终端 ME 库存中键、JEI 悬停物品中键是三个入口，分别验证；后两个不能靠“世界取物通过”代替。
- 保留 WTLib 与现有运行环境的按键配置、初始数量及数量界面、确认行为。JEI 入口只处理本次约定的终端上下文与可识别的 JEI ingredient。普通库存槽、天枢专用槽、JEI 编辑/作弊保留输入及其他界面按既有规则分流。
- Shift 中键维护、Ctrl 中键元件配置、样板专用槽的数量编辑先按现有职责处理。整理模组兼容不能再次手动分派一次已经处理的点击。对同一个物理点击，成功路由后只发送一次请求、消费一次事件；不得并行调用 GTLCore 旧请求与 Core 新请求。
- ④的内部请求必须携带可验证的来源菜单会话及请求标识；单个 container ID 或按键冷却不能单独证明会话/幂等性。服务端绑定玩家和当前菜单实例，拒绝关闭重开后的迟到包、重放、重复请求；成功打开数量菜单不等于已提交订单。
- 失败语义固定：不属于支持入口的点击不消费，交回既有输入链；已经识别为本次终端动作但来源/网络/权限失效时，终止本次动作并给出可理解的失败反馈，不转发给另一条下单链；打开数量页失败不能记作已受理订单。保持原生/现有确认错误展示，不把异常或未知提交状态伪装成成功。
- JEI 打开数量页时传入原生初始数量；普通整数路径由原生菜单处理，扩展数量沿用现有 Core `BigInteger` API 和 AE2LT 数量适配。协议必须有解码长度、正数及执行能力界限，不能截断、溢出或用客户端显示值覆盖精确值。
- 取物/插入/磁卡执行遵循真实 storage 返回的成功量及能量扣除规则，不能模拟后按请求量假定执行成功，不能对未知提交状态自动重试、退款。最终确认时再次校验来源、网络、玩家来源和提交结果；重复确认不得重复派单。
- 所有世界、菜单、物品与网络状态访问都在服务端游戏线程执行。客户端只负责输入、展示和请求，不决定网络或操作成功。

## 6. ③的数据与磁卡责任

③同时覆盖两类终端的升级安装、开关、过滤、吸取至玩家/ME、移除及保存重开。WUT 的升级、频率/原生绑定、能量、合成网格、工作站库存、样板草稿与配置按现有所有权保留；合并先在副本上检查冲突和容量，不能清空原物品再尝试合并，也不能复制实体物品。

为每个玩家按 WTLib 选择得到的**一个实际终端**执行磁卡，不能按模块数、菜单数或额外 tick 监听重复执行。③核对原版 WTLib 调用加上现有 `TianshuWctIntegration.tick` / magnet mixin 的真实组合，不能只数自己新增的 handler。失效 locator 或卸下磁卡后，缓存不得继续执行；配置读写必须来自同一物品。

## 7. 文件归属（后续写入锁定范围）

| 步骤 | 仓库及允许写入范围 | 明确边界 |
| --- | --- | --- |
| ① | Core `docs/contracts/tianshu-terminal-interaction-v1*`；构建交付在 Core `build/tianshu-terminal-contract-v1/` | 本版无 API 缺口，因此没有 Java/API/Mixin/依赖修改；原有 API 签名冻结 |
| ②、⑤、⑥、⑧ | 双仓只读 | 只交审查结论，不改实现、测试、构件或契约 |
| ③ | AE2LT `src/main/java/com/moakiee/ae2lt/{integration/ae2wtlib,mixin/ae2wtlib,menu,client/tianshu,logic/tianshu/terminal}/` 及相关客户端适配、资源、构建接入和本仓测试/测试夹具 | AE2LT 的全部菜单/界面/WTLib/数据合并/磁卡由③统一负责；不修改 Core |
| ④ | Core 新的 `src/main/java/com/moakiee/thunderbolt/integration/jei/`、`core/terminal/`、`network/terminal/`、`mixin/compat/gtl/`；相应 `src/test/`、本仓行为夹具；必要的 `build.gradle`、`src/main/resources/thunderbolt.mixins.json`、`META-INF/mods.toml`、Core 自身语言资源 | 这些是④内部实现保留路径；实际最小文件清单由④交付。复用已冻结 API，不改 AE2LT 文件，不编辑本文或新增共用协议类型；若必须修改冻结 API，退回①② |
| ⑦ | AE2LT 的本地构件接入、联调配置和验收记录 | 不越仓修改 Core；联调失败退③/④修复并重审 |

Core `api/crafting/CraftingRequestMenu.java`、`CraftingAmountMenu.java` 及其他现有公共 API 不因本次分工归④任意更改。原有 `mixin/ae2/crafting/` 实现若需追加内部生命周期验证，可由④修改并接受⑥审查，但不得改变本契约行为和冻结签名。可选兼容的客户端类只在对应模组存在且匹配能力时加载；Core 不新增 AE2LT、WTLib、Curios、GTLCore 或 JEI 的运行时硬依赖，不把客户端类型放进通用公共签名。

共同资源配置文件只在各自仓库由对应实现负责人维护。①不修改上述后续实现文件，因此③、④没有共享写入文件。构件字节一经交付保持不变；④构件必须用新标识及哈希，不能覆盖①制品。

## 8. 固定本地构件及消费方式

本版保持模组版本 `2.0.4`，使用独立 Maven 坐标：

```text
com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1-gtl-tianshu-contract-v1-5926ab84:2.0.4
```

仓库位于 Core `build/tianshu-terminal-contract-v1/maven/`，完整发行 JAR、POM、逐文件 SHA256、文档副本和清单位于 `build/tianshu-terminal-contract-v1/delivery/`。该目录不作为源码提交；①不发布 MavenLocal 或远端仓库，不覆盖普通 GTL `2.0.4` 坐标。POM 为现有 artifact-only 发表方式，不携带开发环境的 `_mapped_official` 依赖。

③/⑦可在 **AE2LT 自己批准的构建接入** 中添加这个只含对应 group/module 的本地 Maven 仓库，并用已有项目属性选择：

```text
-Pthunderbolt_artifact_id=thunderbolt-reborn-forge-1.20.1-gtl-tianshu-contract-v1-5926ab84
-Pthunderbolt_version=2.0.4
--init-script <Core构件目录>/delivery/use-contract.init.gradle
-PthunderboltContractRepo=<Core构件目录>/maven
```

库地址通过本地构建参数/init script 接入，不将另一成员绝对工作区路径或凭据写进源码。编译用该 Maven JAR/POM，经 ForgeGradle 正常 deobf；真实运行使用 delivery 中完整重混淆 JAR，不能拿 slim JAR 或开发 class 目录代替。两者 SHA256 必须一致。启动前确认只有一个 `thunderbolt` 模组副本。

## 9. 验证、尚未执行与下游门禁

①执行 Java 17 下 Core `compileJava`、当前工作区 JUnit 与项目 `check/build/verifyReleaseJar`，核对最终发行包的 Java 17 字节码、Mixin/refmap、嵌套依赖、元数据及许可证，记录精确结果。测试中本来存在的可选跳过如实记录；此前留存的日志/成绩不算本次验证。无运行时代码更改，本步不新增只检查文档或模拟源码字符串的测试。

③、④分别编译并做相关回归，经⑤、⑥通过后，⑦在隔离世界执行：两类独立终端对照、各自单天枢模块 WUT、双模块共存/切换；背包/快捷栏/已安装的 Curios、多终端/多网络；现存/仅可合成物品；真实世界取到快捷栏、ME 界面取物或发起合成、JEI 到数量/确认并真正提交、磁卡真实吸取；开关/过滤/目标/移除/合并/保存重开；断电、失联、访问拒绝、换菜单和重复点击；真实客户端连接专服同步及守恒。

本步上述游戏场景全部为**未执行**。如果目标 AE2/整合包没有某项权限机制，先记录实际机制与适用性，不将“没有配置拒绝场景”算作通过。

④开始实际 JEI 挂点实现前确认目标 GTLCore/JEI 构件及现有输入优先级；若与参考 JAR 不同，调整本仓私有可选适配，不更改共用契约。缺少目标运行构件时，只能记录相应联调未执行，不能宣称兼容已验证。⑧最终审查证据，⑨仅在全部门禁通过后给出功能完成结论。
