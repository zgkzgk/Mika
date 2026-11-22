# Mika 项目规则

## 项目概览

Mika 是一个开源（Apache-2.0）的 Android AI 助手：用户一句话下达任务，它在后台虚拟屏上操作任意 App 和网页直到完成（无障碍 + 虚拟屏 + 沙箱）。仓库根目录即项目根，`applicationId = com.wangzan101.mika`，minSdk 29 / targetSdk 35 / compileSdk 36。

## 技术栈

- Kotlin 2.1.20 + JVM 工具链 17（构建需 JDK 17 和 Android SDK）
- AGP 8.7.3，JetBrains Compose（compose-bom 2024.12.01，material3）
- 依赖版本统一在 `gradle/libs.versions.toml` 管理
- 关键库：OkHttp、kotlinx-serialization、kotlinx-coroutines、protobuf-lite（trace 导出）、Shizuku、ML Kit OCR、ZXing
- 辅助语言：Go（`tools/relay/` 网关）、Python 3.10+（`tools/mika/` CLI、`tools/i18n/` 翻译工具）

## 模块结构

```
:core   纯 Kotlin(JVM)，不含安卓 —— 主循环、工具引擎、上下文、记忆、知识、触发器、运行记录；
        提示词/内置知识/技能在 core/src/main/resources/（knowledge/apps、knowledge/sites、skills、prompts）
:app    Android 宿主 —— Compose 界面、前台服务、虚拟屏、无障碍、WebView、沙箱（app/src/main/cpp/mikarun）、
        :core 各接口的安卓实现
tools/  relay(Go 远程网关)、mika(Python CLI)、i18n(翻译)、sandbox(原生沙箱)
eval/   真机评测任务集；docs/ 用户与开发者文档（开发者文档为中文）；openspec/ 变更提案（proposal/design/tasks）
```

代码包前缀：`core/…` = `core/src/main/kotlin/com/wangzan101/mika/core/`，`app/…` = `app/src/main/java/com/wangzan101/mika/`。

## 架构硬性约束（有测试守着，违反会挂）

- **依赖方向**：能力包 `core/cap/<能力>/` 依赖核心；核心不引用任何能力；能力之间互不引用；只有装配层 `core/assembly/` 同时认识几种能力。`PackageDependencyTest` 强制检查。
- **工具引擎与界面解耦**：`core/tool/`（ToolDispatcher、AgentTool 等）不引用界面状态 `core/uistate/`，只经 `core/tool/SurfaceLayer.kt` 接口（屏 id、工具、参数、结果）交互。
- **唯一执行入口**：工具调用经 ToolDispatcher → ActionExecutor；运行输入（插话/回答/确认/交回/取消）统一走 `core/run/RunInbox.kt`。
- **知识索引构建期校验**：`core/src/main/resources/knowledge/` 头部在构建时由 `KnowledgeIndex` 校验并生成索引，格式不对构建失败（格式见 docs/dev/knowledge-format.md）。

## 构建与测试

```bash
./gradlew :app:assembleDebug                                # debug 构建
./gradlew :core:test --stacktrace                           # core 单测（CI 同款）
./gradlew :core:test :core:cliTest :app:testDebugUnitTest   # 全部单测（cliTest 需 Python3.10+，可 -PmikaPython=python3）
./gradlew :core:relayTest                                   # 真实 Go 网关集成测试（需 Go）
./gradlew updateReadme                                      # 重新生成 README 的 generated 段落
```

改代码请带上单测；PR 会跑 CI（core / cli / app / relay 四个 job，见 .github/workflows/ci.yml）。

## 开发约定

- 仓库语言氛围为中文：代码注释、开发者文档（docs/dev/）、Gradle 任务描述均为中文；贡献说明在 `.github/CONTRIBUTING.md`。
- 文本文件钉死 LF（.gitattributes）：金样、提示词、README 逐字节比较；工作树不是 LF 会导致测试与金样不一致，先 re-checkout 再跑测试。
- README 中 `<!-- generated:xxx:start -->` 到 `end` 之间由 `./gradlew updateReadme` 生成，**不要手改**；改了根 README.md 后需按 `ReadmeTranslationsTest` 的输出同步 `docs/readme/` 各语言译文并更新顶部指纹。
- 金样（快照）测试：循环消息序列（loop-snapshots）与提示词表面（prompt-surface），用 `./gradlew :core:test -PupdateSnapshots` 重新生成后再人工审查。
- 知识贡献（App/网站 usage knowledge）是纯 Markdown 文本，放 `core/src/main/resources/knowledge/`，是无需写代码的主要贡献方式。
- 沙箱原生二进制与捆绑运行时已入库，重建见 `tools/sandbox/README.md`。
- 新功能/修复的变更提案放在 `openspec/changes/<change-id>/`（proposal.md、design.md、tasks.md）。

## 关键文档

- `docs/dev/architecture.md` —— 按代码现状写的整体结构（找代码先看这个）
- `docs/dev/roadmap.md` —— 已完成 / 下一步 / 已知问题
- `docs/dev/knowledge-format.md` —— 知识文件格式
- `docs/dev/README.md` —— 开发者文档索引与阅读顺序
- `docs/README.md` —— 用户文档索引
