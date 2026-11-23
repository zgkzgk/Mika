package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.label
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** phone_run 的等待命令:等文字出现(命中/超时)与等页面安静(达成/超时),都带超时上限、保留插话打断语义。 */
class PhoneRunTest {
    /** 跑一条 phone_run;子动作一律成功(等待之后还有收尾的读屏)。 */
    private suspend fun run(screen: ScriptedScreen, commands: String): JsonObject {
        val dispatcher = toolDispatcher(screen)
        dispatcher.ctx.sub = SubActions { ToolExecution(buildJsonObject { put("ok", true); put("snapshotId", "p1") }) }
        return PhoneRun.execute(Args(PhoneRun.def, Json.parseToJsonElement("""{"commands":$commands}""").jsonObject), dispatcher.ctx).content
    }

    private fun JsonObject.steps() = this["steps"]!!.jsonArray.map { it.jsonObject }

    @Test fun waitTextAppearHitsWhenPortReportsIt() = runTest {
        val screen = ScriptedScreen(listOf(Screen("com.x", listOf(label("@e1", "首页", 100)))), waits = listOf(true))
        val r = run(screen, """[{"op":"wait","targetText":"已发送","timeoutMs":8000}]""")
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertEquals("true", r.steps().single()["ok"]!!.jsonPrimitive.content)
        // 路由到端口的等文字条件,带上超时
        assertEquals(listOf("wait appear 已发送 8000"), screen.calls)
    }

    @Test fun waitTextAppearTimesOutAsConditionUnmet() = runTest {
        val screen = ScriptedScreen(listOf(Screen("com.x", listOf(label("@e1", "首页", 100)))), waits = listOf(false))
        val r = run(screen, """[{"op":"wait","targetText":"已发送"}]""")
        assertEquals("false", r["ok"]!!.jsonPrimitive.content)
        assertEquals("condition_unmet", r["reason"]!!.jsonPrimitive.content)
        assertEquals("condition_unmet", r.steps().single()["error"]!!.jsonPrimitive.content)
    }

    @Test fun waitStableSucceedsWhenPageSettles() = runTest {
        val screen = ScriptedScreen(listOf(Screen("com.x", listOf(label("@e1", "首页", 100)))), waits = listOf(true))
        val r = run(screen, """[{"op":"wait","stable":true,"timeoutMs":8000}]""")
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertEquals("true", r.steps().single()["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("wait stable - 8000"), screen.calls)
    }

    @Test fun waitStableTimesOutAsConditionUnmet() = runTest {
        val screen = ScriptedScreen(listOf(Screen("com.x", listOf(label("@e1", "首页", 100)))), waits = listOf(false))
        val r = run(screen, """[{"op":"wait","stable":true}]""")
        assertEquals("false", r["ok"]!!.jsonPrimitive.content)
        assertEquals("condition_unmet", r["reason"]!!.jsonPrimitive.content)
        assertEquals("false", r.steps().single()["ok"]!!.jsonPrimitive.content)
    }
}
