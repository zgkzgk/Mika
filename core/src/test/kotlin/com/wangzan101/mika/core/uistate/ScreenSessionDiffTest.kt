package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连续读屏的 visibleText:首屏全量,同屏只报差异(new/gone/moved/unchanged),换屏、要求全量、diff 失败兜底全量。 */
class ScreenSessionDiffTest {
    /** 树读不到字(自己画的界面),靠 OCR 给可见文字;app 可在两次读之间切换。 */
    private class OcrScreen(var lines: List<VisibleTextLine>, var app: String = "com.example") : ScreenPort by FakeScreen() {
        override suspend fun snapshot() = ScreenSnapshot("p1", app, emptyList(), 1080, 2400, unreadableApp = app)
        override suspend fun peek() = snapshot()
        override suspend fun readVisibleText() = lines
    }

    private fun texts(v: Any?): List<String> =
        (v as JsonArray).map { ((it as JsonObject)["text"] as kotlinx.serialization.json.JsonPrimitive).content }

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = VisibleTextLine(text, listOf(l, t, r, b), 0.9f)

    @Test fun firstReadIsFullThenSameAppReportsDiff() = runTest {
        val screen = OcrScreen(listOf(line("张三", 10, 10, 200, 40), line("明天见", 10, 60, 300, 100)))
        val d = toolDispatcher(screen)
        val first = d.executeRead(Part.ToolCall("1", "phone_observe", "{}")).content
        assertTrue("首屏应是全量数组", first["visibleText"] is JsonArray)
        assertEquals(listOf("张三", "明天见"), texts(first["visibleText"]))
        screen.lines = listOf(line("张三", 10, 10, 200, 40), line("已发送", 10, 60, 300, 100))
        val second = d.executeRead(Part.ToolCall("2", "phone_observe", "{}")).content
        val diff = second["visibleText"]!!.jsonObject
        assertEquals(listOf("已发送"), diff["new"]!!.jsonArray.map { (it as JsonObject)["text"]!!.jsonPrimitive.content })
    }

    @Test fun unchangedScreenReportsEmptyMarker() = runTest {
        val screen = OcrScreen(listOf(line("张三", 10, 10, 200, 40)))
        val d = toolDispatcher(screen)
        d.executeRead(Part.ToolCall("1", "phone_observe", "{}"))
        val second = d.executeRead(Part.ToolCall("2", "phone_observe", "{}")).content
        assertEquals(buildJsonObject { put("unchanged", true) }, second["visibleText"]!!.jsonObject)
    }

    @Test fun appSwitchFallsBackToFull() = runTest {
        val screen = OcrScreen(listOf(line("张三", 10, 10, 200, 40)))
        val d = toolDispatcher(screen)
        d.executeRead(Part.ToolCall("1", "phone_observe", "{}"))
        screen.app = "com.other"
        val second = d.executeRead(Part.ToolCall("2", "phone_observe", "{}")).content
        assertTrue("换应用应回全量", second["visibleText"] is JsonArray)
        assertEquals(listOf("张三"), texts(second["visibleText"]))
    }

    @Test fun fullEscapeHatchReturnsFullText() = runTest {
        val screen = OcrScreen(listOf(line("张三", 10, 10, 200, 40)))
        val d = toolDispatcher(screen)
        d.executeRead(Part.ToolCall("1", "phone_observe", "{}"))
        screen.lines = listOf(line("张三", 10, 10, 200, 40), line("李四", 10, 60, 300, 100))
        val full = d.executeRead(Part.ToolCall("2", "phone_list_elements", """{"full":true}""")).content
        assertTrue("full:true 应回全量", full["visibleText"] is JsonArray)
        assertEquals(listOf("张三", "李四"), texts(full["visibleText"]))
    }

    @Test fun diffFailureFallsBackToFull() = runTest {
        val screen = OcrScreen(listOf(line("张三", 10, 10, 200, 40)))
        val session = toolDispatcher(screen).ctx.session
        val snap = screen.snapshot()
        val prev = listOf(line("张三", 10, 10, 200, 40))
        val cur = listOf(line("张三", 20, 20, 220, 50))
        // 没有上一屏:全量
        assertTrue(session.visibleJson(snap, prev, full = false) is JsonArray)
        // diff 出错:兜底全量
        val out = session.visibleJson(snap, cur, full = false, diff = { _, _ -> throw IllegalStateException("diff") })
        assertTrue("diff 出错应兜底全量", out is JsonArray)
        assertEquals(1, (out as JsonArray).size)
    }
}
