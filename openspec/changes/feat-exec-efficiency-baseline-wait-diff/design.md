# 执行效率首期三件套 设计

对应 proposal：度量基线、复合等待、读屏精简。来源 spec：`.claude/artifacts/designs/optimize-execution-efficiency.md`（AC-1~6），实施按 dev-tdd（每行为先 RED 后 GREEN）。

## ADR：读屏精简采用「只 diff OCR 可见文字、元素树保持全量、`full:true` 逃生口」

- **Decision**：diff 只作用于 `visibleText`（OCR 行集）；元素树（含 ref/坐标）每次全量。等待条件走 `phone_run` wait op 扩展 `stable:true`，复用 `ScreenPort.waitFor` 语义。度量基线建在 FakeChatModel/ScriptedScreen 之上的 JVM 测试侧。
- **Drivers**：模型可操作性不被破坏（决定性）——diff 若遮挡点按锚点，模型「看得省、点不了」；与现有测试基建（FakeChatModel / ScriptedScreen / LoopSnapshot）兼容；PackageDependencyTest 架构约束不破。
- **Alternatives**：
  - 全量 diff（树 + 文字 + ref 重映射）：省得最多，但 ref 稳定性无保证，错了模型点错位置 → rejected。
  - 连续 observe 自动 diff、无参数：调用方零改动，但模型需要完整屏时没有显式逃生口 → rejected（其合理部分并入 `full:true`）。
- **Consequences**：观察文本格式变化 → 金样重生成（已做，diff 仅工具描述与 schema）；省的 token 少于全量 diff（元素树不变）——首期风险换稳定性。

## 关键设计

### 1. ScreenDelta（`core/uistate/ScreenDelta.kt`，纯函数）

`between(previous, current) -> Result(new, gone, moved)`：按行文本分组做多重集合匹配——同文本同 bounds=不变；同文本不同 bounds=移动（报**当前屏**新坐标）；文本不在上一屏=新增；消失行在「行数净减少」时才报（移动的行接替旧位置，不重复报消失）。`json()`：无变化给 `{"unchanged":true}`，否则只放非空组，行格式沿用 `VisibleText.json`。

### 2. ScreenSession 接入（`visibleJson`）

以 `packageName ?: unreadableApp`（网页面再拼 url）为键保存上一屏行集：首屏、键变化、`full:true`、diff 异常（`try/catch` 兜底）四种情况输出全量；OCR 读不到时清掉 base（下一屏回全量，绝不因 diff 丢关键信息）。`observe()` 与 `withScreen()` 两处模型可见的 `visibleText` 都走它；记忆/视觉等内部消费仍用全量。

### 3. stable 等待（`PhoneRun` wait op）

`stable:true` 与 `targetText` 二选一（都缺/`assert` 带 stable 报 ArgError）；`timeoutMs` 校验沿用 0–10000；stable 路由 `ctx.screen.waitFor("stable", null, timeout)` 并用 `waitOrSteer` 包裹，插话时返回 null → `SKIPPED_FOR_NEW_MESSAGE`，与文字等待语义一致。超时结果 `ok:false, error:condition_unmet`，由模型决定下一步。

### 4. 度量基线（`LoopMetrics`，仅测试侧）

指标：往返次数（`FakeChatModel.requests.size`）、输入字符数（system + 逐消息各 part 文本长）、读屏文本大小（带 snapshotId 的 ToolResult 内容长）、总耗时（nanoTime，不入库）。基线 `baseline.json` 存场景名/往返/输入/读屏四项；普通运行比对场景齐全 + 往返精确匹配，`-PupdateSnapshots` 重写。场景 = 3 个任务 ×（朴素版逐轮读屏 / 等待版 `phone_run` 连续命令），AC-2 在测试中断言等待版往返严格小于朴素版。

### 5. 消费者兼容

`phone_collect` 按 JsonArray 读 `visibleText` → 内部读屏传 `{"full":true}`；沙箱 `dump` → `phone_list_elements` 强制全量（脚本消费，无对话历史可比 diff）；`SandboxTools` 其余命令参数透传不变。

## 测试

- `ScreenDeltaTest`（6）：新增/消失/移动/无变化/空组序列化/多重集合计数。
- `ScreenSessionDiffTest`（5）：首屏全量→同屏 diff、无变化标记、换应用全量、`full:true` 逃生口、diff 失败兜底（注入抛错的 diff lambda）。
- `PhoneRunTest`（4）：等文字命中（路由 appear + timeout 透传）、等文字超时、等安静命中、等安静超时；`ScriptedScreen` 增 waits FIFO 队列驱动。
- `LoopMetricsTest`（2）：六场景指标产出 + 基线比对；AC-2 严格下降。
- 回归：`:core:test` 全绿（4269）；金样重生成后人工审查（diff 仅工具描述/schema 参数/tools hash）。

## 后续方向

真机人工验收清单；数字性能目标（等基线产出后另立 spec）；全量 diff 的 ref 重映射可行性研究；按需工具加载。
