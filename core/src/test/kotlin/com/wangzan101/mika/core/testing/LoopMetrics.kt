package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Part
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File

/** 一次基准场景的四项指标:模型往返次数、发给模型的输入字符数、读屏文本大小、总耗时。 */
data class LoopMetric(val scenario: String, val roundTrips: Int, val inputChars: Long, val screenChars: Long, val elapsedMs: Long)

/**
 * 执行效率基准的指标收集器:只挂测试装配(假模型的请求记录、工具结果里的观察文本),不进生产装配。
 * 输入字符数以字符近似 token;读屏文本大小 = 带 snapshotId 的工具结果(观察文本)的长度。
 * 数字只用于同题同剧本的前后相对对比,不写绝对承诺。
 */
object LoopMetrics {
    /** 基线文件里的一份指标(总耗时随机器波动,不入库)。 */
    private data class Baseline(val scenario: String, val roundTrips: Int, val inputChars: Long, val screenChars: Long)

    private val json = Json { prettyPrint = true }
    private val file = File("src/test/resources/loop-metrics/baseline.json")
    private val update = System.getProperty("mika.updateSnapshots") == "true"

    /** 跑一次场景并统计四项指标;指标同时打印到测试输出。 */
    suspend fun measure(scenario: String, model: FakeChatModel, block: suspend () -> TaskState): LoopMetric {
        val start = System.nanoTime()
        val end = block()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val roundTrips = model.requests.size
        val inputChars = model.requests.sumOf { r ->
            r.system.length.toLong() + r.messages.sumOf { m -> m.parts.sumOf { part -> partChars(part) } }
        }
        val screenChars = model.requests.asSequence()
            .flatMap { it.messages.asSequence() }
            .flatMap { it.parts.asSequence() }
            .filterIsInstance<Part.ToolResult>()
            .filter { "snapshotId" in it.content }
            .sumOf { it.content.length.toLong() }
        val m = LoopMetric(scenario, roundTrips, inputChars, screenChars, elapsedMs)
        println("[loop-metrics] $scenario: roundTrips=$roundTrips inputChars=$inputChars screenChars=$screenChars elapsedMs=$elapsedMs outcome=$end")
        return m
    }

    /**
     * 与基线比对:场景与本轮运行的一致,逐场景往返次数相等(基准剧本变了会在这里拦下)。
     * -PupdateSnapshots 时重写基线文件。
     */
    fun checkBaselines(metrics: List<LoopMetric>) {
        val fresh = buildJsonObject {
            put("scenarios", buildJsonArray {
                metrics.forEach { m -> add(buildJsonObject {
                    put("scenario", m.scenario); put("roundTrips", m.roundTrips)
                    put("inputChars", m.inputChars); put("screenChars", m.screenChars)
                }) }
            })
        }
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(json.encodeToString(JsonElement.serializer(), fresh))
            return
        }
        if (!file.exists()) fail("没有基线 ${file.path};用 ./gradlew :core:test --tests '*LoopMetricsTest' -PupdateSnapshots 生成")
        val recorded = Json.parseToJsonElement(file.readText()).jsonObject["scenarios"]!!.jsonArray
            .map { e -> val o = e.jsonObject
                Baseline(o["scenario"]!!.jsonPrimitive.content, o["roundTrips"]!!.jsonPrimitive.int,
                    o["inputChars"]!!.jsonPrimitive.long, o["screenChars"]!!.jsonPrimitive.long) }
        assertEquals("基线里的场景与本轮运行的不一致", recorded.map { it.scenario }, metrics.map { it.scenario })
        metrics.forEach { m ->
            val base = recorded.single { it.scenario == m.scenario }
            assertEquals("场景 ${m.scenario} 的往返次数和基线对不上(基准剧本变了吗?)", base.roundTrips, m.roundTrips)
        }
    }

    private fun partChars(p: Part): Long = when (p) {
        is Part.Text -> p.text.length.toLong()
        is Part.Thinking -> p.text.length.toLong()
        is Part.ToolCall -> (p.name.length + p.argsJson.length).toLong()
        is Part.ToolResult -> p.content.length.toLong()
        is Part.Image -> 0L
    }
}
