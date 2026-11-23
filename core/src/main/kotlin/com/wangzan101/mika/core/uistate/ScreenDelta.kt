package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 两屏 OCR 可见文字的差异。按行文本做多重集合匹配:
 * 同文本同位置 = 不变(不输出);同文本不同位置 = 移动(报当前屏的新坐标);
 * 文本不在上一屏 = 新增;上一屏有、这一屏没有 = 消失。
 * 只作用于发给模型的观察文本;元素树保持全量,模型的点按锚点(ref/坐标)不经过这里。
 */
internal object ScreenDelta {
    data class Result(val new: List<VisibleTextLine>, val gone: List<VisibleTextLine>, val moved: List<VisibleTextLine>) {
        val changed: Boolean get() = new.isNotEmpty() || gone.isNotEmpty() || moved.isNotEmpty()
    }

    fun between(previous: List<VisibleTextLine>, current: List<VisibleTextLine>): Result {
        val prevByText = previous.groupBy { it.text }
        val curByText = current.groupBy { it.text }
        val newLines = mutableListOf<VisibleTextLine>()
        val movedLines = mutableListOf<VisibleTextLine>()
        val goneLines = mutableListOf<VisibleTextLine>()
        for ((text, cur) in curByText) {
            val prev = prevByText[text].orEmpty()
            if (prev.isEmpty()) { newLines += cur; continue }
            // 同文本按位置逐个对上;对不上的当前行是移动
            val unmatched = prev.toMutableList()
            val displaced = mutableListOf<VisibleTextLine>()
            for (line in cur) {
                val i = unmatched.indexOfFirst { it.bounds == line.bounds }
                if (i >= 0) unmatched.removeAt(i) else displaced += line
            }
            movedLines += displaced
            // 移动的行接替了旧位置:同行文字报移动,不再重复报消失;行数真的少了才报消失
            if (displaced.isEmpty()) goneLines += unmatched
        }
        for ((text, prev) in prevByText) if (text !in curByText) goneLines += prev
        return Result(newLines, goneLines, movedLines)
    }

    /** 无变化给 {"unchanged":true};有变化只放非空组,行格式沿用 VisibleText.json。 */
    fun json(r: Result): JsonObject = if (!r.changed) buildJsonObject { put("unchanged", true) } else buildJsonObject {
        if (r.new.isNotEmpty()) put("new", VisibleText.json(r.new))
        if (r.gone.isNotEmpty()) put("gone", VisibleText.json(r.gone))
        if (r.moved.isNotEmpty()) put("moved", VisibleText.json(r.moved))
    }
}
