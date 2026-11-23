package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.testing.FakeChatModel
import com.wangzan101.mika.core.testing.FakeScript
import com.wangzan101.mika.core.testing.LoopMetric
import com.wangzan101.mika.core.testing.LoopMetrics
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.button
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.testing.field
import com.wangzan101.mika.core.testing.label
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * 执行效率基准:用假模型剧本驱动真的 AgentLoop 跑典型任务,统计四项指标
 * (模型往返次数、每轮输入字符数、读屏文本大小、总耗时)并与基线文件比对。
 * 每个场景有朴素版(逐轮读屏)和等待版(phone_run 连续命令):等待版的往返次数必须严格小于朴素版。
 * 指标只用于同题同剧本的前后相对对比,不写绝对承诺。
 * 基线:core/src/test/resources/loop-metrics/baseline.json;
 * 重新生成:./gradlew :core:test --tests '*LoopMetricsTest' -PupdateSnapshots
 */
class LoopMetricsTest {
    private fun loop(model: FakeChatModel, tools: ToolDispatcher, maxSteps: Int = 20) = AgentLoop(
        model, tools, SystemPrompt.identity(), maxSteps, clock = { CLOCK }, zone = ZoneOffset.UTC,
    )

    private suspend fun runScenario(
        name: String, screens: List<Screen>, script: FakeScript.() -> Unit, waits: List<Boolean> = emptyList(),
    ): LoopMetric {
        val model = fakeModel("main", script = script)
        return LoopMetrics.measure(name, model) {
            val end = loop(model, toolDispatcher(ScriptedScreen(screens, waits = waits))).run(GOALS.getValue(name.substringBeforeLast("_wait")))
            assertTrue("$end", end is TaskState.Succeeded)
            assertEquals(0, model.remaining)
            end
        }
    }

    @Test fun baselineScenariosMatchRecordedValues() = runTest {
        val metrics = listOf(
            runScenario("settings_toggle", SETTINGS_SCREENS, SETTINGS_NAIVE),
            runScenario("chat_send", CHAT_SCREENS, CHAT_NAIVE),
            runScenario("scroll_read", SCROLL_SCREENS, SCROLL_NAIVE),
            runScenario("settings_toggle_wait", SETTINGS_SCREENS, SETTINGS_WAIT),
            runScenario("chat_send_wait", CHAT_SCREENS, CHAT_WAIT),
            runScenario("scroll_read_wait", SCROLL_SCREENS, SCROLL_WAIT, waits = listOf(true)),
        )
        metrics.forEach { m ->
            assertTrue("${m.scenario} 往返次数", m.roundTrips > 0)
            assertTrue("${m.scenario} 输入字符数", m.inputChars > 0)
            assertTrue("${m.scenario} 读屏文本大小", m.screenChars > 0)
            assertTrue("${m.scenario} 总耗时", m.elapsedMs >= 0)
        }
        LoopMetrics.checkBaselines(metrics)
    }

    /** AC-2:同一剧本改用等待条件的连续命令后,往返次数严格小于朴素版。 */
    @Test fun waitSequencesUseFewerRoundTripsThanNaive() = runTest {
        for (name in listOf("settings_toggle", "chat_send", "scroll_read")) {
            val naive = runScenario(name, SCREENS.getValue(name), SCRIPTS.getValue(name))
            val wait = runScenario("${name}_wait", SCREENS.getValue(name), WAIT_SCRIPTS.getValue(name),
                waits = if (name == "scroll_read") listOf(true) else emptyList())
            assertTrue("$name: wait 版往返 ${wait.roundTrips} 应严格小于朴素版 ${naive.roundTrips}",
                wait.roundTrips < naive.roundTrips)
        }
    }

    private companion object {
        const val CLOCK = 1_761_300_000_000L
        const val APP = "com.x"
        const val CHAT = "com.chat"

        val SETTINGS_SCREENS = listOf(
            Screen(APP, listOf(label("@e1", "设置", 80), button("@e2", "蓝牙", 300), button("@e3", "WLAN", 400))),
            Screen(APP, listOf(label("@e1", "蓝牙", 80), label("@e2", "已开启", 300))),
        )
        val CHAT_SCREENS = listOf(
            Screen(CHAT, listOf(label("@e1", "张三", 40), field("@e2", "发消息"), button("@e3", "发送", 950))),
            Screen(CHAT, listOf(label("@e1", "张三", 40), field("@e2", "明天见", hint = false), button("@e3", "发送", 950))),
            Screen(CHAT, listOf(label("@e1", "张三", 40), label("@e4", "已发送", 600))),
        )
        val SCROLL_SCREENS = listOf(
            Screen(APP, listOf(label("@e1", "第 1 屏", 200))),
            Screen(APP, listOf(label("@e1", "第 2 屏", 200))),
        )

        val SETTINGS_NAIVE: FakeScript.() -> Unit = {
            call("phone_observe")
            call("phone_tap", "ref" to "@e2", "snapshotId" to "s1")
            call("phone_observe")
            call("phone_observe")
            answer("蓝牙已开启,蓝牙页显示「已开启」。")
        }
        val SETTINGS_WAIT: FakeScript.() -> Unit = {
            call("phone_run", "commands" to listOf(
                mapOf("op" to "tap", "targetText" to "蓝牙"),
                mapOf("op" to "wait", "targetText" to "已开启"),
            ))
            answer("蓝牙已开启,蓝牙页显示「已开启」。")
        }
        val CHAT_NAIVE: FakeScript.() -> Unit = {
            call("phone_observe")
            call("phone_type_text", "text" to "明天见", "ref" to "@e2", "snapshotId" to "s1")
            call("phone_tap", "ref" to "@e3", "snapshotId" to "s1")
            call("phone_observe")
            answer("已给张三发了「明天见」,聊天里显示「已发送」。")
        }
        val CHAT_WAIT: FakeScript.() -> Unit = {
            call("phone_run", "commands" to listOf(
                mapOf("op" to "type", "targetText" to "发消息", "text" to "明天见"),
                mapOf("op" to "tap", "targetText" to "发送"),
                mapOf("op" to "wait", "targetText" to "已发送"),
            ))
            answer("已给张三发了「明天见」,聊天里显示「已发送」。")
        }
        val SCROLL_NAIVE: FakeScript.() -> Unit = {
            call("phone_observe")
            call("phone_scroll", "direction" to "down")
            call("phone_observe")
            call("phone_observe")
            answer("这一页读完了。")
        }
        val SCROLL_WAIT: FakeScript.() -> Unit = {
            call("phone_run", "commands" to listOf(
                mapOf("op" to "scroll", "direction" to "down"),
                mapOf("op" to "wait", "stable" to true),
            ))
            answer("这一页读完了。")
        }

        val GOALS = mapOf("settings_toggle" to "看看蓝牙开没开", "chat_send" to "给张三发明天见", "scroll_read" to "往下翻读完这一页")
        val SCREENS = mapOf("settings_toggle" to SETTINGS_SCREENS, "chat_send" to CHAT_SCREENS, "scroll_read" to SCROLL_SCREENS)
        val SCRIPTS = mapOf(
            "settings_toggle" to SETTINGS_NAIVE, "chat_send" to CHAT_NAIVE, "scroll_read" to SCROLL_NAIVE,
        )
        val WAIT_SCRIPTS = mapOf(
            "settings_toggle" to SETTINGS_WAIT, "chat_send" to CHAT_WAIT, "scroll_read" to SCROLL_WAIT,
        )
    }
}
