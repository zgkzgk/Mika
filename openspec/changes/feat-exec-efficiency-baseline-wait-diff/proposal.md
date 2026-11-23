# 执行效率首期三件套：度量基线、复合等待、读屏精简

## Why

任务执行效率由两个最大变量决定：**模型往返次数**（主循环每轮只执行第一个工具调用，`AgentLoop`）与**每轮输入大小**（读屏每次全量输出，无 diff 机制）。真实循环消息序列里，朴素的「观察→动手→观察」写法每轮重发整段历史；读屏可见文字（OCR）是观察文本里最大的一块，逐轮全量重复。

JVM 基准（`LoopMetricsTest`，假模型口径）验证了优化的量级：同一剧本改用 `phone_run` 连续命令后，往返次数 5 → 2，每轮输入字符 ~14.5 万 → ~4.9 万，读屏文本 2.5–3.5K → ~0.6K。

## What Changes

- **度量基线**：core 测试侧新增 `LoopMetrics` 指标收集器（往返次数、输入字符数、读屏文本大小、总耗时）与 3 个基准场景（settings_toggle / chat_send / scroll_read，各有朴素版与等待版）；优化前数值提交为 `core/src/test/resources/loop-metrics/baseline.json`，`-PupdateSnapshots` 可重生成。等待版往返次数在单测中断言严格小于朴素版（AC-2），基线拦截场景级回归。
- **复合工具等待条件**：`phone_run` 的 wait op 新增 `stable:true`（等页面安静），与现有 `targetText`（等文字出现/消失）并列；都带超时上限（0–10s）并保留插话打断语义（SKIPPED_FOR_NEW_MESSAGE）。stable 路由到 `ScreenPort.waitFor("stable", null, timeout)`，复用现有等待子系统，不新建机制。
- **读屏精简（diff）**：`ScreenDelta` 纯函数对比两屏 OCR 可见文字（按文本多重集合匹配：同文同位=不变、同文异位=移动报新坐标、无匹配=新增/消失）；`ScreenSession` 以应用/页面为键保存上一屏行集，首屏、换屏、`full:true`、diff 失败四种情况兜底全量。**元素树保持全量**——模型的点按锚点（ref/坐标）不经过 diff，这是硬边界。

## Capabilities

### New Capabilities

- `loop-metrics`: JVM 基准剧本集与指标收集（仅测试装配，不进生产路径）。
- `screen-delta`: OCR 可见文字差异计算与序列化（`{"unchanged":true}` / `{"new":[…],"gone":[…],"moved":[…]}`）。

### Modified Capabilities

- `phone-run`：wait op 支持 `stable:true`；schema 与 `prompts/tools/phone_run.md` 同步。
- `observe` / `list-elements`（手机与网页同构）：结果加 `full:true` 参数；`phone_observe.md` / `list_elements.md` 补充 diff 语义。
- `phone_collect`：内部读屏传 `full:true`（按 JsonArray 消费）；沙箱 `dump` 同样强制全量（无对话历史可对 diff）。

## Impact

- core/uistate：新增 `ScreenDelta.kt`；`ScreenSession` 增 `visibleJson` 与 `observe(fullText)`；`ReadTools` 两工具加 `full` 参数。
- core/cap/phone：`PhoneRun` wait 分支扩展；`PhoneCollect` 传 `full:true`。
- core/cap/sandbox：`SandboxTools` 的 `dump` 强制全量。
- core/src/test：新增 `LoopMetrics`、`LoopMetricsTest`、`PhoneRunTest`、`ScreenDeltaTest`、`ScreenSessionDiffTest`；`ScriptedScreen` 增 waits 结果队列。
- 金样：prompt-surface（工具描述 + schema 两个 boolean 参数）与 loop-snapshots（tools hash）重生成，diff 仅上述两处来源。
- 首期不做：按需工具加载、并行工具调用、端侧优化、全量 diff（元素树 diff 需先解决 ref 稳定性，见后续方向）。
