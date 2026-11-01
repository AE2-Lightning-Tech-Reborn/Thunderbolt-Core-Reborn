# Core 2.0.4-gto.3：双 AE2 ABI 与既有批量契约修复

本次处理配套 LT 交回的三个独立阻塞：七处数量表调用、两个规划器逻辑键的
final 方法冲突，以及既有 NeoECO 桥的七项契约回归。目标为 Minecraft 1.20.1、
Forge 47.4.20、Java 21，兼容 AE2-GTO `15.2610.31` 与 `15.2610.40`。

## 数量表调用

`.31` 的 `AEKeyLongMap` 声明 `put/addTo(AEKey,long):long`；`.40` 改为继承
`IdentityKeyLongMap` 的 `IdentityKey` 参数实现。共同父类
`Reference2LongOpenHashMap` 的原始 long 方法使用 `Object` 擦除描述符。

新增内部 `BatchKeyAmounts`，通过共同父类发出
`put/addTo(Object,long):long` 虚调用，仍分派到 AE2 自身的桥接实现。
容器继续使用 `AEKeyLongMap<AEKey>`；身份定位、返回旧值、饱和加法和快速迭代
沿用原生实现，不引入装箱、反射写入或另一份数量账本。

全部七处调用已替换：`ParallelBatchCpuHelper` 的两条取料路径与共享输出额度
共四处 `put`、两处 `addTo`，以及 `BatchExecutor.outputAmounts` 的一处 `addTo`。
最终发行字节码中七处均调用新辅助类，辅助类仅包含两个共同父类的擦除调用；
旧的 `AEKeyLongMap.put/addTo(AEKey,long)` 调用为零。

## 规划器键

`DemandKey` 和 `LateBoundOutputKey` 同时移除内容 `equals` 与 `hashCode`，使用身份
相等，兼容 `.40` 的 `IdentityKey.final hashCode()`。

- `IdOnlyInputGroups` 通过独立的 `DemandIdentity(identity, unitAmount)` 值键查表，
  每次图导出为同一需求复用唯一的 `DemandKey`。不同模板单位仍分别建组，且共享同一物理库存。
- `LateBoundOutputKey.Pool` 在一次图导出内按目录键复用逻辑节点；输入、晚绑定副产物
  和 `dropSecondary()` 使用同一池。池不进入全局缓存，不串联不同规划的逻辑身份。
- 逻辑键仍在 CPU 任务、数量报告与序列化前投影为物理键；直接序列化继续拒绝。
  严格输入不得使用晚绑定同 ID 产物的约束保留。

这避免只删除 `hashCode` 却保留内容 `equals` 造成集合契约破坏，也避免每次创建
不同逻辑节点导致供需图断开。原有同 ID、不同单位、严格兄弟输入、晚绑定副产物与
精确数量回归均通过。

## 既有 NeoECO 桥的契约回归

本次仅修正仓库已经存在的 `NeoEcoGtoBatchAdapter`。没有新增或恢复 NeoECO
注册、依赖、配方或提交测试夹具。实际 GTO 的 `ECOFastPathDispatchProvider`
不继承 `ICraftingProvider`，发现检查按其真实接口形状执行。

1. 类发现被 `SecurityException` 拒绝时返回不可用。
2. 使用前核对工厂静态性、公共声明类、实例成员、参数及返回类型；检查能量和预约
   接口的抽象回调全集，拒绝不支持的额外抽象回调。
3. 两种动态代理通过 `InvocationHandler.invokeDefault` 调用合法默认方法。
4. 不确定提交异常仍原样传播，预备批次仍只能提交一次；该路径不执行普通回退。
5. 明确拒收且协议已保证回滚后，以同一任务 UUID 和另一份输入副本尝试一次普通提交；
   本 tick 容量降为一份，新物理 tick 恢复能力检查。
6. 空任务或缺 UUID 的准备容量为零，不调用外部准备或普通派发。
7. 非法接纳数量在提交前抛出 `IllegalArgumentException`，保留模板隔离与范围检查。

七项原失败断言完整重跑，覆盖畸形 API 的四个变体、默认回调、部分接收、拒收回退、
下一 tick 恢复、空任务、非法数量及不确定提交。原 LT 测试源码和协议资源只读引用，
原测试源码副本与输入逐字节一致；Core 隔离入口将测试启动器转接至本项目 bootstrap，
用真实 AE2 红石物品键代替 LT 的数量键，其余断言不变。该入口及资源输出仅在本地
忽略目录与 `build/`，没有加入发行包或源码提交。

## 验证结果与边界

| 本次检查 | 结果 |
| --- | --- |
| 完整 JUnit，AE2 `.31` | 1,676 项：1,674 通过、2 跳过，零失败或错误 |
| 冷 JVM 专项 | CP-SAT 1 项、物料重放 20 项、补料 2 项均通过 |
| Python 发行契约 | 12 项通过 |
| `check build compileGameTestJava`、重混淆与 `verifyReleaseJar` | 通过 |
| 最终发行包映射副本，AE2 `.31` | 批量/规划 ABI 14/14；NeoECO 契约及实物发现 8/8 |
| 同一最终发行包映射副本，AE2 `.40` | 批量/规划 ABI 14/14；NeoECO 契约及实物发现 8/8 |
| 发行包二进制审计 | 七处替换齐全、旧调用为零；公开批量与适配器 API 描述符不变 |
| 发行包内容审计 | 557 个自有类均为 Java 21；无重复 ZIP 条目或 QA 夹具；许可证、署名、唯一 refmap、Mixin 与 JarJar 元数据齐全 |
| Maven Local、项目 `repo/` 与冻结发行包 | 正式 JAR 的 SHA-256 一致 |

两项完整套件跳过仍为缺少可选 EAEP SuperMatrix 实物和 Useless 实际 API 制品。
14 项专用 ABI 测试也包含在完整套件中，各轮及各依赖组合不相加为独立覆盖数量。

双版运行使用最终发行包映射副本，排除 `main` 的编译输出。JVM 类加载日志确认
数量表来自对应 AE2，辅助类、逻辑键及实际被调用的 NeoECO 适配器来自发行包映射
副本。映射只转换 Minecraft 成员名称，没有删除依赖的 final 方法或修改 AE2 容器。
本轮属于真实依赖 ABI、库存/规划算法与协议验证，尚未执行新制品的完整整合包
ModLauncher 加载、实体机器、独立客户端及跨 JVM 保存恢复；这些交回 LT 复验。

正式依赖输入：

| 制品 | SHA-256 |
| --- | --- |
| AE2 `15.2610.31` | `f63917bf5a2ca0692c0550ad2d045991f7a493d89021bfb30b51026d4ad31a3d` |
| AE2 `15.2610.40` | `b0d97544e618d73ec0c8d8e54df36351bf7f98f7e1984c6455d81658b6320b5c` |
| NeoECO `20.5.2-gto.1`，仅用于可选接口发现 | `c535ea3256a1ab5b0ad6025a28b5ead90e295b3fe7c2a1198246c1369a4d7f8b` |

常规复现入口：

```powershell
.\gradlew.bat check build compileGameTestJava '-Dnet.minecraftforge.gradle.check.certs=false' --offline --no-daemon --max-workers=2
.\gradlew.bat ae2AbiTest '-Dnet.minecraftforge.gradle.check.certs=false' --offline --no-daemon
.\gradlew.bat ae2AbiTest '-PthunderboltAe2AbiJar=<正式 AE2 .40 JAR>' '-Dnet.minecraftforge.gradle.check.certs=false' --offline --no-daemon
python -m unittest discover -s .github/scripts -p test_release.py
```

`ae2AbiTest` 在 JUnit 发现前限定专用测试类，普通最低版本夹具中覆盖 `hashCode`
的自定义键不进入 `.40` 运行。首轮发现范围错误与临时发行验证 init 脚本的类加载
失败保留在本地原始日志；它们没有进入业务断言，也没有计为通过。
修正入口后的两版 14 项及八项契约均有完整逐项结果和类来源证据。

本地原始日志、XML、最终制品字节码审计、类加载来源、隔离入口和交接清单位于
`.gradle/dual-ae2-abi/`，不加入提交。最终制品固定在其 `release/` 目录。

## 发行与交回

新坐标为 `com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1:2.0.4-gto.3`，
已发布至 Maven Local 与本项目 `repo/`；不覆盖 `.2`，不推送远端。
正式发行文件为 `thunderbolt-forge-1.20.1-2.0.4-gto.3.jar`，5,471,662 字节，SHA-256：

```text
1ebff68ab9a2c7e041b8ca8338acb1a00aa9f5eacfa6604b4f55f2cd04be85a9
```

配套 LT 应固定该哈希，复验双版本原生批量、共享种子、拒收退款、NeoECO 协议与
真实 FX、Dev25 exact 及跨 JVM 保存恢复。沿用原通过条件与失败记录，完整整合包
验收结论由新制品实跑结果决定。
