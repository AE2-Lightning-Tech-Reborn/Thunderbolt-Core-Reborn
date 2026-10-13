# Minecraft 26.1.2

验证环境：Java 25、NeoForge 26.1.2.109、AE2 26.1.12-beta。此分支保留原有架构，仅适配不兼容的构建、存储、资源、渲染及模组 API。

## 构建

将三个仓库的 `26.1.2` 分支放在同级目录，目录名分别为 `Thunderbolt-Core-Reborn`、`AE2-Lightning-Tech-Reborn`、`AE2LT-Packaged-Pattern-Provider-Reborn`，按此顺序执行：

```sh
bash ./gradlew check jar
```

使用 Java 25。首次构建需要联网解析依赖；依赖已缓存后可加 `--offline`。LT 默认读取相邻 Thunderbolt 的构建 JAR，PP 默认读取相邻 LT 和 Thunderbolt 的构建 JAR；自定义位置可用项目 Gradle 属性覆盖。构建结果在各项目的 `build/libs`。运行所需的第三方模组不随这三个 JAR 一起分发。

## 适配与验证

基线 main：`3798e5b0300eddb19706cd9352ba0a29f957048b`。

- 更新 Gradle、Java、NeoForge、AE2 依赖及注册、ValueInput/ValueOutput、AEKey 相关接口。
- 保留规划器、批次执行、核心存储与 AE2 Mixin 的原有结构。
- 2026-09-23 验证：常规 `check jar` 通过 782 项测试；`bash ./gradlew -PthunderboltFmlTests=true test` 另通过 45 项依赖 FML 的测试。
- 与 LT、PP 联合专用服务器及实际客户端加载通过；客户端完成大整数有线/无线终端计划、提交、CPU 状态、取消和精确退款流程。

LT 调度性能结果见 LT 的移植说明，保持原验收门槛。

## alpha 最终状态同步（2026-10-13）

以 alpha `7559206..0ed711d` 的最终差异为依据，同步规划器的批次优化、索引复用、反馈预处理、种子组合与排程提示，以及预备输入分配、原生 AE2/AdvancedAE CPU 每 tick 的共享派发排程。保留 26.1.2 的 AEKey、ValueIO 与 CPU 存档接口，按规划器和批次执行两批提交。

AdvancedAE 接口已核对远端 `main` 的 `5ad43ee1e5f7`，使用实际 `tickCraftingLogic` 与批次提供器签名。未移植缺少对应版本的 UselessMod 兼容，也未恢复已不匹配当前运行版本的旧 NeoEco 批次接口。

本次 `check jar` 通过；FML 测试共 59 项，58 项通过，1 项跳过。LT 的 CPU GameTest 联合加载此核心、AE2、AdvancedAE、ExtendedAE 和 Applied Flux，25 项必需测试全部通过。
