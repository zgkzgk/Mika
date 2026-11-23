package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** 两屏 OCR 可见文字的差异:按文本匹配,新增/消失/移动(报当前屏的新坐标),无变化给空标记。 */
class ScreenDeltaTest {
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = VisibleTextLine(text, listOf(l, t, r, b), 0.9f)

    @Test fun linesAbsentFromPreviousAreNew() {
        val d = ScreenDelta.between(emptyList(), listOf(line("已发送", 100, 600, 900, 650)))
        assertEquals(listOf(line("已发送", 100, 600, 900, 650)), d.new)
        assertEquals(emptyList<VisibleTextLine>(), d.gone)
        assertEquals(emptyList<VisibleTextLine>(), d.moved)
    }

    @Test fun linesAbsentFromCurrentAreGone() {
        val d = ScreenDelta.between(listOf(line("已发送", 100, 600, 900, 650)), emptyList())
        assertEquals(emptyList<VisibleTextLine>(), d.new)
        assertEquals(listOf(line("已发送", 100, 600, 900, 650)), d.gone)
        assertEquals(emptyList<VisibleTextLine>(), d.moved)
    }

    @Test fun sameTextDifferentPlaceIsMovedWithCurrentBounds() {
        val d = ScreenDelta.between(listOf(line("确定", 100, 300, 900, 360)), listOf(line("确定", 100, 800, 900, 860)))
        assertEquals(emptyList<VisibleTextLine>(), d.new)
        assertEquals(emptyList<VisibleTextLine>(), d.gone)
        assertEquals(listOf(line("确定", 100, 800, 900, 860)), d.moved)
    }

    @Test fun identicalScreensReportNoChange() {
        val lines = listOf(line("确定", 100, 300, 900, 360), line("取消", 100, 400, 900, 460))
        val d = ScreenDelta.between(lines, lines)
        assertEquals(false, d.changed)
        assertEquals(buildJsonObject { put("unchanged", true) }, ScreenDelta.json(d))
    }

    @Test fun jsonKeepsOnlyNonEmptyGroups() {
        // 只有移动:json 里只有 moved 一组
        val onlyMoved = ScreenDelta.between(listOf(line("搬家", 10, 50, 100, 70)), listOf(line("搬家", 10, 200, 100, 220)))
        assertEquals(setOf("moved"), ScreenDelta.json(onlyMoved).jsonObject.keys)
        // 三类都有
        val mixed = ScreenDelta.between(
            listOf(line("旧的", 10, 10, 100, 30), line("搬家", 10, 50, 100, 70)),
            listOf(line("新的", 10, 10, 100, 30), line("搬家", 10, 200, 100, 220)),
        )
        assertEquals(setOf("new", "gone", "moved"), ScreenDelta.json(mixed).jsonObject.keys)
    }

    @Test fun duplicateTextsAreMatchedByCount() {
        val same = line("已发送", 10, 10, 100, 30)
        val d = ScreenDelta.between(listOf(same, same), listOf(same))
        assertEquals(emptyList<VisibleTextLine>(), d.new)
        assertEquals(listOf(same), d.gone)
        assertEquals(emptyList<VisibleTextLine>(), d.moved)
    }
}
