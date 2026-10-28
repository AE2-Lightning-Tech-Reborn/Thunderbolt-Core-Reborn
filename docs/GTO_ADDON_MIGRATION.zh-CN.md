# 普通版附属的 GTO 迁移工具

> 2026-10-21：NeoECO 专用兼容已移除，工具不再接受其原始 JAR。支持的附属仅为下表三项。


本项目提供独立的 `gto-addon-patcher.jar`，迁移以下实际检查过的 JAR 到 AE2-GTO 15.2610.8（AppliedE 需要 Thunderbolt V2 规划）。工具不安装到 `mods`，不修改原始 JAR，不修改 AE2 和 GTOCore。

| 原始文件 | SHA-256 | 修改类数 |
| --- | --- | --- |
| `advancedae-1.3.6-1.20.1.jar` | `e216f95ac73eb4c1e8f3db22cc3d30f37f2612a7cb1762b9ebce148d0d5657b2` | 3 |
| `ae2-crafting-tree-1086241-7182165.jar` | `0996cd736e5a951593f94125c059710800056de8def77995deb7ba6b50099367` | 1 |
| `appliede-0.14.3.jar` | `76a863b993a77c6939c34f78385b92cdb08312701220552c06865924aaf0021b` | 2，另移除 3 个旧生命周期 Mixin |

工具将旧 `patternTimes(): Map` 调用改为 GTO 的 `Object2LongMap` 返回值；旧 `CraftingPlan(..., Map)` 构造调用先复制为 `Object2LongOpenHashMap`，再调用 GTO 构造器。这样可以接受 `HashMap`、不可变 `Map`，并保留完整 long 数量。

AdvancedAE 的 `CraftingService` Mixin 同时将 `currentlyCrafting` 的影子字段和读取描述符迁移为 `AEKeySet`。AdvancedAE 的四处旧 `LocalCapture` 改用 MixinExtras `@Local(ordinal = 0)`；计算、提交、CPU 列表、请求量的原逻辑及注入位置保留。



AppliedE 使用已有的单份转化样板和 EMC 等级换算样板，代替旧合成树临时创建的整批样板。迁移移除临时样板的创建、菜单清理、CPU 清理三个 Mixin，并为 `TransmutationPattern` 实现 GTO 公共 `IDetails` 输入暂存接口；每个样板持有独立、非空的 `KeyCounter[]`。终端同步 Mixin 的局部变量捕获也迁移为 `@Local`。修正 Forge 依赖表名，要求安装 Thunderbolt 和 GTOCore 26.10.5。EMC 单价、知识库、存取核算和模块输出队列保留原逻辑。

**AppliedE 必须在网络的算法提供器中选用 Thunderbolt V2，并使它排在原生算法之前。** GTO 原生规划器拒绝自定义转化样板，补齐 `IDetails` 只能满足 CPU 的输入接口，不能修复原生规划器的类型限制。已实测 V2 规划、实体 GTO CPU 执行、EMC 扣费与网络交付；未验证 AppliedE 跨等级极端数量订单及任务重启恢复。不要把启动通过当作原生规划已兼容。

构建及使用（Java 21）：

```powershell
.\gradlew.bat gtoAddonPatcherJar
java -jar build/compat-tools/gto-addon-patcher.jar advancedae-1.3.6-1.20.1.jar advancedae-1.3.6-1.20.1-gto.jar
java -jar build/compat-tools/gto-addon-patcher.jar ae2-crafting-tree-1086241-7182165.jar ae2-crafting-tree-1.1.1-gto.jar
java -jar build/compat-tools/gto-addon-patcher.jar appliede-0.14.3.jar appliede-0.14.3-gto-v2.jar
```

将生成文件替换实例中的相应原文件，客户端与服务端使用相同副本。保留原文件备份，但同一 `mods` 目录只放一个版本。AdvancedAE 和 Crafting Tree 的资源、Mixin 配置、refmap 和嵌套依赖保持原始内容；AppliedE 额外修改自己的 Mixin 配置与依赖元数据；输出额外包含记录输入哈希的 `META-INF/thunderbolt-gto-migration.txt`。工具拒绝未知哈希、签名包、覆盖已有输出和原地写入。

此次检查的 ExtendedAE Plus 1.5.5 不在工具支持范围：仍依赖 GTO 已删除的规划/执行实现及 JEI 辅助类。上游 `1.20.1-gto` 分支也仍声明旧 `ForcedCraftingPlan.patternTimes(): Map`，且通过条件插件停用若干 GTO 冲突功能，不能直接替换为当前 AE2 的完整兼容版本。工具继续拒绝该原始 JAR，避免生成仅能启动而功能不完整的迁移包。

实机测试证据和仍未解决的范围见 [GTO_COMPATIBILITY_AUDIT.zh-CN.md](GTO_COMPATIBILITY_AUDIT.zh-CN.md)。生成的第三方 JAR 仅用于本地验证；仓库保存迁移工具源码，不提交第三方二进制。
