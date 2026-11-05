# Core 2.0.4-gto.4：移植 EJECT / ElementalCraft 兼容修复

来源为普通 Forge `1.20.1` 的
[`d67e9560cff4bde405730cf5f9baa82542852cc6`](https://github.com/AE2-Lightning-Tech-Reborn/Thunderbolt-Core-Reborn/commit/d67e9560cff4bde405730cf5f9baa82542852cc6)。
在已验收的 `2.0.4-gto.3` 上移植本次新增修复；GTO 双 AE2 数量表、规划器键与
既有批量结算修复保留，没有恢复普通 NeoECO 的适配、注册、依赖、配方或测试夹具。

## 修复

旧实现将 EJECT 的 `getCapability` HEAD 拦截注入 Forge 公共父类
`CapabilityProvider`。这会使 ItemStack 等子类进入 Mixin 0.8.5 的继承重绑定路径，
影响其他模组的接口父方法调用。上游针对 ElementalCraft 两个附魔方法的
`IncompatibleClassChangeError: ... must be Methodref constant` 改为：

- 仅以 `BlockEntity` 为 Mixin 目标，合并一个 `getCapability(Capability, Direction)`
  覆写。EJECT 命中时返回代理能力，未命中时调用 `super.getCapability`。
- 保留 priority 2000、服务器与侧面检查、离线 `ABSENT` / `REJECT`、嵌套旁路及
  `ThreadLocal` 防递归的原有语义；宿主查询异常后继续在 `finally` 清理防递归状态。
- 公共父类不再是任何已配置 Core Mixin 的目标；没有修改 ElementalCraft 原件或其接口调用。

生产 Mixin 与上游该文件一致。原有源码字符串契约改为读取编译后的类与 Mixin 配置，
核对目标类型、优先级、父类、原始方法描述符和父类回退的 `INVOKESPECIAL`。
另新增五项真实 GameTest，与已有三项注册生命周期测试一起验证：

1. 注册侧面的物品／流体能力转发，其他侧面、无侧面及注销后的原生能力回退。
2. 离线拒收返还全部物品输入、拒绝流体且不更改原生存储。
3. `ABSENT` 与嵌套旁路保持原生能力所有权，退出旁路后恢复 EJECT。
4. 自引用不会递归，宿主异常传播且不会泄漏防递归标记。
5. 实际 ItemStack 的两种附魔查询保持正确，普通剑为零级、附魔后为三级。

原生能力由 Forge 的 `AttachCapabilitiesEvent<BlockEntity>` 安装；测试对象继承
实际转换后的 BlockEntity 方法，未复制 EJECT 实现。附魔测试调用真实 ElementalCraft
Mixin 修改后的 ItemStack，不用同名接口夹具替代。

## 目标环境与验证

Minecraft 1.20.1、Forge 47.4.20、Microsoft OpenJDK 21.0.10。正式 Core、独立 QA
与真实依赖在新建隔离实例中运行；每轮记录全部模组 JAR 哈希并确认运行前后不变。

| 实例 | GTOCore | AE2-GTO | ElementalCraft | DataPack Anvil | 最终结果 |
| --- | --- | --- | --- | --- | --- |
| Dev23 | 26.10.10 | 15.2610.31 | 1.20.1-6.0.1 | 1.20.1-5.0.1 | **8/8 GameTest 通过**，53.77 秒 |
| Dev25 | 26.10.18 | 15.2610.40 | 1.20.1-6.0.1 | 1.20.1-5.0.1 | **8/8 GameTest 通过**，55.60 秒 |

两轮均逐项核对八个唯一结果、`failed=0`、非零注册数、正常保存退出及 JVM 退出码。
五项新增测试和三项已有生命周期测试分别覆盖行为与持久化注册，不把重复运行相加
为独立覆盖数量。独立 QA 没有加入正式发行包。

旧版负向对照使用冻结的 `.3` 正式包、同一最终 QA 与 Dev23 的真实依赖，八项测试中
七项通过，只有 `itemStackInterfaceSuperEnchantmentCallsRemainValid` 按预期失败。
实际错误为 `IncompatibleClassChangeError: ... getEnchantmentLevel(...) must be Methodref constant`，
堆栈包含 ElementalCraft 的 `MixinItemStack`。导出的旧版 ItemStack 字节码同时显示
`getEnchantmentLevel` / `getAllEnchantments` 的 `invokespecial` 使用了错误的接口方法引用。
本轮 181.24 秒内正常保存退出，未超时，输入 JAR 哈希保持不变；测试入口正确判为失败，
没有把 JVM 正常退出当作测试通过。与上表 `.4` 的双版通过结果一起确认修复确实覆盖原故障。

| 其他检查 | 结果 |
| --- | --- |
| 完整 JUnit | 1,677 项：**1,675 通过、2 跳过、零失败／错误** |
| 冷 JVM 专项 | CP-SAT 1 项、物料重放 20 项、补料 2 项通过 |
| `compileJava`、`check build`、`compileGameTestJava`、重混淆与 `verifyReleaseJar` | 通过 |
| Python 发行脚本契约 | 12 项通过 |
| 正式包检查 | 557 个自有类均为 Java 21；无重复条目或 QA 夹具；许可证、署名、Mixin 配置、唯一 refmap 及 JarJar 元数据齐全 |
| 本地 Maven／项目 `repo/`／冻结发行包 | JAR SHA-256 一致 |

两项 JUnit 跳过仍为缺少可选 EAEP SuperMatrix 与 Useless 实际接口输入。
隔离实例沿用对应 GTO 的依赖及配置，并加入经官方哈希核对的 ElementalCraft 和
DataPack Anvil。既有 LT 冻结包精确要求 Core `.3`，因此本次移除隔离副本中的 LT
和旧 QA；没有修改 LT 的源码、发行包或已有验收实例。这些结果只证明本次
Core / EJECT / ElementalCraft 组合，不替代 `.4` 与后续 LT 配套的全部行为验收。

初始 QA 的测试名筛选、日志级别及重混淆继承信息曾导致零结果或
`EjectRegistrationSavedData.save` 链接错误。仅修正独立 QA 入口与映射类路径，
没有更改生产断言、接收数量、测试总数或超时。原始失败保留；最终表格仅采用
制品固定且运行前后哈希相同的两轮完整结果。

测试启动按真实服务端 tick 推进；Forge 正式环境禁用自动 GameTest tick 时，
独立 QA 每个真实 tick 仅推进一次。原生 GTO / EAEP 等依赖原有的可选 Mixin 警告
保留在原日志，未据此声明整个模组组合没有告警。

本盘空间不足的首次依赖复制未启动 JVM。仅回收本次复制且与原件哈希相同的文件，
实机实例改在本任务独立的 `D:/CodexTaskData/thunderbolt-core-gto-eject-20261102/`。
源码与构建仍在当前工作区，其他项目及既有验收实例未修改。该隔离目录保存各轮
输入清单、逐项结果、原始日志与冻结制品；工作区 `.gradle/eject-port-20261102/`
保存构建与入口，不加入源码提交。

## 制品

新 Maven 坐标为：

```text
com.moakiee.thunderbolt:thunderbolt-reborn-forge-1.20.1:2.0.4-gto.4
```

正式文件 `thunderbolt-forge-1.20.1-2.0.4-gto.4.jar`，5,471,709 字节，SHA-256：

```text
734d9bf152a66dee5bf69397106b6808a64305969459ba60c2e8f7139c08c7cd
```

使用独立的 artifact-only Maven 任务发布已验证的同一个 JAR，未在发布时重新打包；
版本 `.3` 的坐标与原验收包保持不变。

开发和汇总提交保留在本地。本次不更新远端 PR，也不发布 Release 或推送标签。
