# NeoECO 专用兼容移除（2026-10-21）

按要求清除当前 GTO 分支全部 NeoECO 专用兼容。当前构建不提供 NeoECO 适配；AdvancedAE、Crafting Tree、AppliedE、EAEP 的既有边界保持原样。

移除生产 `compat/neoeco` 包及全局注册、加载期 `gto_neoeco_compat.js` 和 coremod 注册、NeoECO 测试依赖与版本属性、协议测试和夹具，以及离线工具的专用转换逻辑和输入哈希。保留 `gto_material_cache.js` 与其注册、公共批量接口和其他附属迁移。旧 NeoECO 生成副本和已编译类已移出项目；旧报告加历史标记，原始测试证据保留。

## 验证

- 从源代码独立构建：`remainingCompatRegression compileGameTestJava verifyReleaseJar gtoAddonPatcherJar` 通过。94 项回归中 93 通过、1 项实际 EAEP 协议测试因未提供对应 JAR 跳过，失败 0；未重跑完整规划器套件。
- 新发布包 597 个条目，扫描条目名和解压后内容，NeoECO 引用为 0。与清理前发布包相比删除 17 个条目，仅修改 coremod 注册和 `OptionalBatchProviders.class`；其他条目逐个哈希一致。
- 离线迁移工具拒绝原版 NeoECO 20.3.0 SHA-256，且不创建输出。其他附属迁移与幂等性回归通过。
- 未安装 NeoECO 的隔离 GTO 专用服务器：Java 21.0.10、Forge 47.4.20、GTOCore 26.10.5、AE2-GTO 15.2610.8、AE2LT 2.1.2-gto.1，端口 25609，新世界。CPU 桥接、库存去重/精确数量/挂载顺序、TB 规划和原生回退检查通过；批量套件 **8/8 通过，启动失败 0**。包含 AppliedE 实体 CPU 交付、输出端注销、批量部分接收、拒收退款及异常不重放。旧外部夹具中的 NeoECO 可选用例明确跳过，不计为通过。全部维度保存，JVM 和运行器返回 0。
- 本轮未重跑客户端测试。未修改相邻 LT 项目、其他游戏实例或上游仓库。

## 制品与证据

发布包：`build/libs/thunderbolt-forge-1.20.1-2.0.3-gto.1.jar`。

SHA-256：`d722c5e09faea08cdbaac21cd584c660db24f181e3ee81f9fc5e0e309d31ee63`。

独立工具：`build/compat-tools/gto-addon-patcher.jar`，仍仅用于 AdvancedAE、Crafting Tree、AppliedE 的已审计版本。

证据目录：`D:/codex-builds/thunderbolt-remove-neoeco-20261021/`。其中 `unit-summary.json`、`regression/xml/`、`build-regression.log`、`release-audit.json`、`unsupported-addon-rejection.log` 记录构建与扫描；`runtime/runtime-results-removed.json`、`runtime/server-batch-removed.log`、`runtime/mod-hashes.json` 记录实测。`before/` 和 `removed/` 保留清理前文件与制品，方便复核。

复现构建：

```powershell
.\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=1 --system-prop net.minecraftforge.gradle.check.certs=false -I D:/codex-builds/thunderbolt-remove-neoeco-20261021/compat.init.gradle remainingCompatRegression compileGameTestJava verifyReleaseJar gtoAddonPatcherJar
```
