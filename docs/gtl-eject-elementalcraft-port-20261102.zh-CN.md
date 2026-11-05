# Forge 1.20.1 EJECT / ElementalCraft 修复移植

本轮将普通 `1.20.1` 的 `fa6d16e..d67e956` 增量移植到 GTL `3d18f68`。来源为 `d67e9560cff4bde405730cf5f9baa82542852cc6`，该范围只有一项修复。保留当前 Forge 1.20.1、Java 17、AE2 15.4.10、Core 2.0.4 版本与 GTL 坐标；不修改其他项目或恢复 NeoECO 内容。

上游未发布变更日志的修复项在此记录：EJECT 原先注入 Forge 公共父类 `CapabilityProvider`，导致 Mixin 0.8.5 将其他 ItemStack Mixin 的接口 `super` 调用错误改写。ElementalCraft 附魔逻辑使用这种调用结构，可能在加载世界时抛出 `IncompatibleClassChangeError: ... must be Methodref constant`。现在只向 `BlockEntity` 合并 `getCapability` 覆写；命中 EJECT 时返回其能力，未命中时调用父类。优先级 2000、原离线策略、防递归与库存所有权保持。

生产实现 `EjectCapabilityMixin.java` 和现有 `EjectCapabilityMixinPriorityContractTest.java` 与上述上游提交逐字节一致。上游新增了禁止以 `CapabilityProvider` 为目标的约束。由于本项目没有根目录 CHANGELOG，本报告承接上游新增的未发布修复条目。

为验证实际转换行为，新增独立的 `ejectCompatibilityGameTest` 源集和 `tools/eject-compatibility-game-tests.gradle`。其中 ItemStack Mixin 复现 ElementalCraft 的 `IForgeItemStack.super` 调用结构，三个 GameTest 检查附魔结果、普通能力回退、在线投影身份、面限制、注销、循环回退后线程局部状态清理，以及离线物品/流体拒收和 ABSENT 策略。测试夹具不构成生产依赖，发行任务禁止与该验证 profile 混跑。

旧实现的 `interface-super-red-matched-parent` 在实际 Forge 47.4.20 / Mixin 0.8.5 加载后复现：调用 `ItemStack.getEnchantmentLevel` 时抛出原错误，JVM exit=1。该夹具匹配 ItemStack 的 `CapabilityProvider<ItemStack>` 父类，在不增加其他触发条件的情况下测试 EJECT 父类注入引起的错误。

早期记录另行保留：首次 `interface-super-red` 因初始化脚本过早查找 Forge 延迟创建的运行任务而未进入游戏，已改用延迟任务配置。随后 `interface-super-red-ready` 与 `interface-super-green` 的夹具默认继承 Object，导致 Mixin 的 detachedSuper 分支独立触发同类错误，不能用来判断生产修复是否有效。核对实际 Mixin 0.8.5 字节码后，夹具改为匹配目标父类，重新执行上述同夹具旧版/新版对照。附魔返回值、EJECT 断言及预算不变，没有将早期失败计为通过。

所有本地过程证据位于 `build/gtl-eject-port-20261102/`，不加入提交。真实 ElementalCraft JAR 未安装，本轮同类错误复现使用接口调用结构夹具；不据此宣称真实 ElementalCraft 整合包已完成验收。

移植后 `interface-super-green-matched-parent` 三项 GameTest 全部通过，正常保存退出。导出的两个附魔方法仍以 `IForgeItemStack` 为 `invokespecial` owner；BlockEntity 覆写在未命中 EJECT 时明确调用 `CapabilityProvider.getCapability`。现有 GTL 兼容 profile 的 14 项 GameTest 也全部通过并正常退出，包含此前精确预览摘要回归。两组均为 Java 17 / Forge 47.4.20 / AE2 15.4.10，GTL 入口使用既有夹具。

对应文件如下，验证源码/资源不会进入发行包：

- `src/main/java/com/moakiee/thunderbolt/mixin/platform/eject/EjectCapabilityMixin.java`
- `src/test/java/com/moakiee/thunderbolt/ae2/EjectCapabilityMixinPriorityContractTest.java`
- `src/ejectCompatibilityGameTest/java/com/moakiee/thunderbolt/mixin/compatfixture/ItemStackInterfaceSuperMixin.java`
- `src/ejectCompatibilityGameTest/java/com/moakiee/thunderbolt/gametest/EjectCapabilityCompatibilityTests.java`
- `src/ejectCompatibilityGameTest/resources/data/thunderbolt_eject/structures/runtime_empty.nbt`
- `tools/eject-compatibility-game-tests.gradle`
- 本报告。

定向 JUnit 6/6 通过。34 个受保护文件的 SHA256 保持，包括原未跟踪文件、AGENTS.md、既有 NeoECO 源码/测试和两个旧冻结包。磁盘不足期间对本项目自建验证目录启用 NTFS 压缩，保留原始文件及失败证据。

完整 `check assemble verifyReleaseJar` 在 Java 17 / 默认 Forge 47.1.3 / AE2 15.4.10 下通过，耗时 157.339 秒。JUnit 共 1730 项：1728 通过、0 失败、2 跳过；跳过原因为本次未提供可选 EAEP 测试 JAR，以及缺少当前 Useless 新公开 API 的真实 JAR。两组 GameTest 共 17/17 通过，与单元测试分别统计。

发行包审计确认 Java 17 字节码、无重复条目、无 GameTest 类/资源及兼容夹具、Mixin 配置和 refmap 匹配、许可证和 MixinExtras 嵌套依赖完整。冻结包保持 `2.0.4` 版本，使用路径和 SHA256 区分此前制品：

- 完整 JAR：[thunderbolt-forge-1.20.1-2.0.4.jar](../build/gtl-eject-port-20261102/release/thunderbolt-forge-1.20.1-2.0.4.jar)，5470188 字节。
- SHA256：`1bd40221c927b9fc5b26dd1e08fc1ba3f3feb5f7c701e9d23e208793dde4868c`；同目录提供 `.jar.sha256`。
- 结构化验证结果：`build/gtl-eject-port-20261102/summary.json`。
- 旧版失败和新版通过日志分别位于 `interface-super-red-matched-parent/`、`interface-super-green-matched-parent/`；实际转换字节码保存在 `green-itemstack-bytecode.txt`、`green-blockentity-bytecode.txt`。

本轮仅本地提交，不推送。请 ae2lt 在当前 GTL 验收副本中使用该完整包，确认实际加载 SHA256，验证冷启动与载入世界、EJECT 物品/流体投影、离线及注销行为；环境包含真实 ElementalCraft 时，记录版本并检查附魔调用。完整整包和真实 ElementalCraft 发行版未在本轮运行，其余整包验收状态沿用 ae2lt 当前报告。
