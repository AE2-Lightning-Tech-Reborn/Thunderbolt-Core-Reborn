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

LT 原有的四项调度性能失败保留在 LT 的移植说明中，未修改核心算法规避这些门槛。
