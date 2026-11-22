# Mika 项目概览

> 新人上手 / 快速回顾用的总览。按代码现状描述的详细架构见 [docs/dev/architecture.md](docs/dev/architecture.md)；本文是其入口级摘要。

## 1. 项目定位

Mika 是一个开源（Apache-2.0）的 **Android AI 助手**：用户一句话下达任务，它在手机上替人操作任意 App 和网页，直到把事办完。

- **怎么做到不干扰用户**：在后台虚拟屏上打开 App、点按、输入、滚动（无障碍 + 虚拟屏 + 自带 Linux 沙箱），用户可以照常使用手机。
- **不依赖任何 App 的 API**：像人一样看屏幕、找按钮，任何 App / 网站都能操作；内置 50+ 常用 App 与网站的使用知识，让它少走弯路。
- **数据不出手机**：没有 Mika 服务器，对话和屏幕内容只发往用户自己配置的模型服务商；支持主流服务商预设、账号登录（ChatGPT / xAI / OpenRouter）。
- 应用信息：`applicationId = com.wangzan101.mika`，minSdk 29 / targetSdk 35 / compileSdk 36，当前版本 0.1.50。

## 2. 核心功能一览

| 领域 | 能力 |
|---|---|
| 操作手机 App | 读长列表与聊天记录、点按输入、跨屏采集、弹窗处理、手势与系统命令；登录、验证码、支付交还给用户 |
| 内置浏览器 | 私有虚拟显示上的 WebView，桌面版身份、登录态、标签页池、网页接管、下载上传 |
| Linux 沙箱 | 每对话一个工作区（Alpine，arm64），可跑 Python / Node / shell，处理用户发的文件 |
| 当前屏幕 | 双击悬浮胶囊，把用户正在看的屏幕发给 Mika（"这条怎么回？"） |
| 定时与条件触发 | 一次性 / 间隔 / 每天每周计划、只读条件检查、结果回原对话 |
| 电脑连接 | 电脑上的 AI 工具经 CLI / Skill / MCP 把任务交给手机；自托管 Go 网关支持公网远程 |
| 记忆与学习 | 事实、偏好、做法（需批准）加密存储；完成的任务沉淀为个人技能与 App 知识草稿 |
| 语音输入 | 长按说话，用系统语音识别或服务商语音模型 |

## 3. 仓库结构

```
:core    纯 Kotlin(JVM)，不含安卓 —— Agent 主循环、工具引擎、上下文、记忆、知识、
         触发器、运行记录；提示词/知识/技能是 resources（knowledge/apps、knowledge/sites、
         skills、prompts），可在电脑上用单测+假模型跑
:app     Android 宿主 —— Compose 界面、前台服务(TaskService)、虚拟屏、无障碍、WebView、
         沙箱(app/src/main/cpp/mikarun)、:core 各接口的安卓实现
tools/   relay(Go 远程网关)、mika(Python CLI)、i18n(翻译工具)、sandbox(原生沙箱)
eval/    真机评测任务集（调优池 / 保留池）
docs/    用户文档(docs/*.md)与开发者文档(docs/dev/*.md，中文)
openspec/  变更提案（proposal.md / design.md / tasks.md）
plugins/ examples/  Mika 插件
```

路径缩写：`core/…` = `core/src/main/kotlin/com/wangzan101/mika/core/`，`app/…` = `app/src/main/java/com/wangzan101/mika/`。

## 4. 技术栈

- **Kotlin 2.1.20** + JVM 工具链 17；AGP 8.7.3；JetBrains Compose（compose-bom 2024.12.01，material3）
- 版本统一由 `gradle/libs.versions.toml` 管理
- 关键库：OkHttp、kotlinx-serialization、kotlinx-coroutines、protobuf-lite（trace 导出）、Shizuku、ML Kit OCR、ZXing
- 辅助语言：Go（tools/relay 网关）、Python 3.10+（tools/mika CLI、tools/i18n）

## 5. 架构总览

```
 来源   对话界面 │ 定时/条件触发 │ 父任务(子任务)│ 电脑(CLI / MCP)
          └──────────┬──────────┘
          TaskService(前台服务,安卓宿主)
          TaskCenter(每对话一个 Run、并行上限、排队)· RunStore · Outbox
                     │
          TaskRun ── 一次运行:装能力、建 AgentLoop、收尾
                     │
          AgentLoop(只调度)← RunInbox(插话、回答、确认、交回、取消)
            ModelCaller · ContextBuilder/ContextCompressor · TurnPolicy/StopRules
            ToolDispatcher → ActionExecutor(唯一执行入口)
                     │  CapabilityProvider(核心不认识具体能力)
          phone      web       sandbox      (mcp 客户端)
```

要点：

- **一次运行 = 一个 Run**：`RunSpec` 描述目标、来源（Ui/Channel/Trigger/Mcp/Child）、能力子集、预算；阶段从 Queued 到 Ended 可见可中断。
- **运行输入统一进 `RunInbox`**：插话(Steer)、改道(Redirect)、回答(Answer)、确认(Confirm)、交回(HandBack)、取消(Cancel)。插话是补充，已发消息一字不改，历史只增。
- **唯一执行入口**：工具调用经 ToolDispatcher → ActionExecutor，可审计、可审批（Ask 模式默认只在花钱前确认，Autonomous 模式全自动）。
- **能力模块**（`core/cap/`）：phone / web / sandbox / 看图 / 子任务等，由装配层按需装进 Run。
- **上下文**：缓存分区、历史只追加、快满时一次性压缩、用户原话永不裁剪。
- **知识体系**：内置 App/网站知识与技能（`core/src/main/resources/knowledge/`），构建期由 `KnowledgeIndex` 校验头部格式并生成索引，格式不对构建失败。

### 硬性架构约束（有测试守着）

1. 能力包依赖核心，核心不引用任何能力，能力之间互不引用，只有 `core/assembly/` 全认识 —— `PackageDependencyTest` 强制。
2. 工具引擎 `core/tool/` 不引用界面状态 `core/uistate/`，只经 `core/tool/SurfaceLayer.kt`（屏 id、工具、参数、结果）交互。
3. 任务中心规则：每个对话同时只有一个 Run；运行中的新消息默认作为插话；不同对话可并行（有上限），超限排队不丢。

## 6. 构建与测试

需 JDK 17 + Android SDK；CLI 测试需 Python 3.10+，Go 集成测试需 Go。

```bash
./gradlew :app:assembleDebug                                # debug 构建
./gradlew :app:assemblePerf                                 # perf 构建（判流畅度用，非 debuggable）
./gradlew :core:test --stacktrace                           # core 单测（CI 同款）
./gradlew :core:test :core:cliTest :app:testDebugUnitTest   # 全部单测
./gradlew :core:relayTest                                   # 真实 Go 网关全链路集成测试
./gradlew updateReadme                                      # 重新生成 README 的 generated 段落
./gradlew :core:test -PupdateSnapshots                      # 更新金样（loop-snapshots / prompt-surface）
```

CI（`.github/workflows/ci.yml`）跑四个 job：core(JVM) / cli(Python) / app(Android) / relay(Go)，PR 与 main 推送触发。

## 7. 仓库约定（易踩坑）

- **注释与开发者文档均为中文**；仓库氛围是中文。
- **文本文件钉死 LF**（.gitattributes）：金样、提示词、README 逐字节比较；工作树不是 LF 会导致测试挂，先 re-checkout。
- **README 的 `<!-- generated:xxx:start/end -->` 区间由 `updateReadme` 生成，不要手改**；改根 README.md 后需按 `ReadmeTranslationsTest` 输出同步 `docs/readme/` 各语言译文并更新顶部指纹。
- **知识贡献是主要贡献方式**：给 App/网站写 `APP.md` / `SITE.md`（纯 Markdown），放 `core/src/main/resources/knowledge/`，无需写代码（格式见 docs/dev/knowledge-format.md）。
- 改代码请带单测；变更提案放 `openspec/changes/<change-id>/`。

## 8. 当前状态（2026-11）

**已完成**：后台虚拟屏操作手机（内置手机助手、无障碍、复合工具）、内置浏览器（DevTools 真实输入）、Linux 沙箱、Run/收件箱/任务中心运行时、主循环与唯一执行入口、上下文压缩、定时与条件触发、记忆与学习、内置知识体系、Token 用量统计、自托管远程 CLI/MCP 网关、评测任务集。

**进行中 / 下一步**：真机跑真实任务按根因修机制、重新打开核查与审批、手机能力 v2（等待条件、精简观察、按需加载工具）、定时任务后续阶段、更多来源（渠道、MCP 传输层）。

**已知问题（节选）**：开发阶段关闭了最终核查（`reviewFinalAnswer = false`）；崩溃恢复缺"连续异常重启即暂停附属服务"保护；网页任务暂不进经验回放；记忆无云同步。

## 9. 文档索引

| 文档 | 内容 |
|---|---|
| [docs/dev/architecture.md](docs/dev/architecture.md) | 按代码现状写的整体结构（找代码先看这个） |
| [docs/dev/roadmap.md](docs/dev/roadmap.md) | 已完成 / 下一步 / 已知问题 |
| [docs/dev/knowledge-format.md](docs/dev/knowledge-format.md) | App/网站知识与技能文件格式 |
| [docs/dev/README.md](docs/dev/README.md) | 开发者文档索引与阅读顺序 |
| [docs/README.md](docs/README.md) | 用户文档索引 |
| [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md) | 贡献指南（含构建、发布流程） |
