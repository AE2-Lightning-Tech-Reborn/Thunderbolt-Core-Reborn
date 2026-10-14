# GTO 1.20.1 兼容构建

本分支基于 Reborn `1.20.1`，用于 GTO 整合包，不是普通 AE2 版本。

## 配套版本

| 组件 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Java | 21 |
| Forge | 47.4.20 |
| GTO 整包基线 | 0.6.0-dev15，公开 0.6.0 包提交 `3650c7d330ade1b744ade0bc303fe5111eac98fa` |
| GTOCore | 26.10.5 |
| AE2-GTO | 15.2610.8（使用 GTOCore 26.10.5 原包内嵌版本） |
| Thunderbolt | 2.0.3-gto.1 |
| AE2LT | 2.1.2-gto.1 |

客户端与服务端一起替换对应的 TB/LT JAR，移除同 mod ID 的旧版本。GTOCore、GTOLib 与 GTO 自带 AE2 均使用原件；无需修改 GTO。不要把这两个包放进普通 AE2 整合包，也不要把普通 LTPP/其他依赖旧 AE2 API 的附属兼容性视为本次验证范围。

## 不支持混用最新 AE2 源码依赖

公开 GTOCore 26.10.5 原件的 `CreativeCellInventoryMixin` 仍要求 `configured: java.util.Set`。外置 AE2-GTO 15.2610.10 已将其改为 `AEKeySet`，该组合会在 GTO 自身的客户端 Mixin 中失败。因此此构建的编译依赖与运行版本均锁定 15.2610.8；不要单独覆盖 GTO 自带 AE2。本次没有修改 GTO 来绕过这个错误。

## 适配边界

- TB 在 GTO 的合成提交入口捕获库存快照，在其已有工作线程上执行候选算法。候选算法不适用时，调用原有 GTO 计算任务并保留其返回对象及元数据。取消、候选失败和 CRAFT_LESS 继续经过 TB 的有界执行器。
- GTO 已删除的 AE2 模拟库存类迁入 TB 自己的包，保留 LGPL 声明，不向 `appeng` 包补回旧类。
- 普通实体 CPU 任务使用 GTO 原生优化实现。含 LT 超载样板的任务使用 LT 自有调度器，并保留实体 CPU 的容量、协处理器预算、暂停、存档、CraftingLink 身份与产物核算。通过 AE2 CPU 集群安装 GTO 公共 CPU 类的子类，不注入或改写 GTO 类。
- LT 的 JEI 处理器迁入自有包；更新 GTO 的 GenericStack、菜单、数量显示、FastCollection 与 CraftingPlan API。
- 直接挂载的 BigMEStorage 保持 BigInteger 精度。经存储总线等连接其他 ME 网络时，使用 GTO 原生去重查询；这条跨网络路径的单次操作和可见数量受 `Long.MAX_VALUE` 限制。不能把多个指向同一库存的连接当作多份库存。
- AE2-GTO 原生物品键忽略仅 ForgeCaps 不同的身份差异；此构建沿用该语义，不承诺恢复普通 AE2 的 capability-only 区分。

## 构建

使用 Java 21。先构建 TB 并发布到其工作树本地 Maven 仓库：

```sh
./gradlew check jarJar verifyReleaseJar publishMavenJavaPublicationToMavenRepository
```

再在 LT 仓库构建：

```sh
./gradlew check jarJar
```

LT 默认从同级 `../Thunderbolt-Core-Reborn/repo` 查找配套 TB。目录不同时传入 `-Pthunderbolt_local_repository=/绝对路径/repo`。

LT 编译时下载公开 GTOCore 26.10.5 原件，并校验 SHA-256 `8e6260ac596091ca876506e70b7715249b7198ddbd5d833059c3609ac751e465`。也可传入 `-Pgto_core_jar=/绝对路径/gtocore-forge-1.20.1-26.10.5.jar`，仍执行相同校验。此依赖只用于编译，绝不嵌入 LT。

分发 `build/libs` 中没有 `-slim`、`-sources`、`-gto-test-agent` 后缀的主 JAR。单元测试用 javaagent 仅将 AE2-GTO 的基础身份 mixin 应用于没有 ModLauncher 的 JUnit JVM，不是生产依赖。

## 实机验证复现

LT 保存了可选测试源集 `src/gtoQa`：

```sh
./gradlew -PgtoQa gtoQaJar
```

将 `build/gto-qa/gto-compat-qa.jar` 与本次两个主 JAR 放入**隔离测试用** GTO 服务端/客户端。测试模组会在服务端启动后于 `(0,100,0)` 附近放置测试结构并自动运行用例；客户端进入世界后检查界面与数量格式、保存截图并自动退出。不要放入正常游戏存档。它不包含在 TB/LT 主 JAR 中。

服务端需出现 CPU、STORAGE、PLANNER 三个 PASS，以及 `GTO_COMPAT_QA PASS total=9 failed=0 startupFailures=0`；客户端需出现 `GTO_CLIENT_QA PASS`。测试覆盖真实 Mixin 后的计算入口、原生回退、共享库存去重、大数库存、挂载顺序、实体 CPU 超载投料/暂停/存档恢复/变体返回/普通任务切换，以及 8 个时轮状态和共享种子循环测试。它不等于整个整合包所有配方和所有可选附属的穷举测试。

## 来源

- GTO 整包：[GregTech-Odyssey 0.6.0 基线](https://github.com/GregTech-Odyssey/GregTech-Odyssey/tree/3650c7d330ade1b744ade0bc303fe5111eac98fa)
- GTOCore 源码：[GTOCore-Main](https://github.com/GregTech-Odyssey/GTOCore-Main)
- AE2-GTO 源码：[Applied-Energistics-2-gto](https://github.com/GregTech-Odyssey/Applied-Energistics-2-gto)
- 迁入的 AE2 LGPL 类见各仓库 `THIRD_PARTY_NOTICES.md`。
