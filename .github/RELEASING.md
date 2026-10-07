# 自动发布

在 GitHub 的 Releases → Create a new release 中创建**新标签**并发布 Release，自动完成构建、GitHub JAR/校验和上传，以及 Modrinth 和 CurseForge 发布。保存草稿不会触发；发布预发布版本同样会上传两个平台。

- 选择发布源码所在分支或提交：NeoForge 1.21.1 使用 `main`，Forge 1.20.1 使用 `1.20.1`。Minecraft、加载器和 Java 版本由该分支的构建配置确定。
- 标签直接作为模组版本，例如 `2.0.2-beta`、`2.0.2`、`2.0.3`、`1.0.0-alpha`。不要求 `v`、Minecraft 或加载器前缀，也不检查标签命名格式。
- 版本含 `alpha` 标记时发布为 alpha；含 `beta` 标记，或勾选 GitHub 的 **Set as a pre-release** 时发布为 beta；其余发布为 release。alpha 优先于预发布勾选。标签控制构建版本，并校验最终 JAR 元数据。
- Release 正文作为两个平台的更新日志，保持 Markdown 内容；创建 Release 时填写正文。不会自动提取 `CHANGELOG.md`。

## Secrets

每个仓库均需在 Settings → Secrets and variables → Actions 添加 `MODRINTH_TOKEN`、`CURSEFORGE_TOKEN`。`GITHUB_TOKEN` 由 Actions 自动提供。缺少任一平台 Token 时，平台发布步骤会明确失败，GitHub 构建产物仍会保留。

AE2LT 和样板供应器通过 GitHub Release 下载构建依赖。若依赖仓库私有，在这两个仓库添加 `RELEASE_DEPENDENCIES_TOKEN`，授予依赖仓库 Contents 读取权限；公共依赖可使用自动提供的 `GITHUB_TOKEN`。Token 不写入仓库。

## 依赖与验证

依赖版本取自发布标签对应的 `gradle.properties`，不替换成 latest。先发布该版本的 Thunderbolt，再发布 AE2LT，最后发布样板供应器；依赖 Release 必须已有对应加载器的 JAR。

依赖下载优先使用直接版本号标签；找不到对应产物时兼容历史 `v<版本>` 和 Forge 的 `forge-1.20.1-v<版本>` 标签。

发布前的工作流检查运行 `.github/scripts/test_release.py` 和 actionlint，不需要平台 Token，也不执行发布。发布流程保留原有 `build -x check` 打包策略；功能测试应在创建发布标签前完成。发布流程验证 JAR 入口、版本、加载器元数据路径和 SHA-256。实际平台上传需发布 Release 后查看 Release 工作流的结果。
