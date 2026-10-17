package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestContextDiagnosticsTest {
    @Test
    fun `ordinary baseline survives a private heartbeat and usage stays linked`() {
        val logs = mutableListOf<String>()
        val diagnostics = RequestContextDiagnostics { tag, message -> logs.add("$tag $message") }
        fun request(source: String, system: String, history: String, tool: String = "time") =
            diagnostics.begin("conversation", source, "ChatCompletions", "private-provider-key").also {
                it.request(body(system, history, tool))
            }
        val first = request("Normal", "private-system", """{"role":"user","content":"private-history"}""")
        val normalId = logs.first().substringAfter("request=").substringBefore(' ')
        first.usage(Json.parseToJsonElement("""{"prompt_tokens":50000,"prompt_tokens_details":{"cached_tokens":0},"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":50000}""").jsonObject)
        assertTrue(logs.last().contains("request=$normalId"))
        assertTrue(logs.last().contains("cached=0 deepseekHit=0 deepseekMiss=50000"))
        request("Heartbeat", "private-heartbeat-system", """{"role":"user","content":"private-history"},{"role":"user","content":"private-trigger"}""", "screen")
        val afterHeartbeat = request("Normal", "private-system", """{"role":"user","content":"private-history"},{"role":"assistant","content":"private-heartbeat-reply"},{"role":"user","content":"private-user-reply"}""")
        assertTrue(logs.any { it.contains("source=Normal") && it.contains("heartbeatRequestsSinceNormal=1") })
        val comparison = logs.last { it.contains("compare=previousNormal") }
        assertTrue(comparison.contains("baseline=$normalId baselineSource=Normal"))
        assertTrue(comparison.contains("sameSystem=true sameTools=true optionDiff=none"))
        assertTrue(comparison.contains("historySharedItems=1 previousHistoryItems=1 historyFirstDiff=items[1].appended"))
        afterHeartbeat.usage(Json.parseToJsonElement("""{"prompt_tokens":50001}""").jsonObject)
        assertTrue(logs.last().contains("cached=missing deepseekHit=missing deepseekMiss=missing"))
        assertFalse(logs.joinToString().contains("private-"))
        request("Normal", "private-system", """{"role":"user","content":"changed-history"}""")
        assertTrue(logs.last().contains("historyFirstDiff=items[0].content"))
        assertTrue(logs.takeLast(3).any { it.contains("heartbeatRequestsSinceNormal=0") })
    }

    @Test
    fun `wire fields distinguish tools options and moved runtime instructions`() {
        val logs = mutableListOf<String>()
        val diagnostics = RequestContextDiagnostics { _, message -> logs.add(message) }
        fun request(body: String) = diagnostics.begin("conversation", "Normal", "ChatCompletions", "provider")
            .request(Json.parseToJsonElement(body).jsonObject)
        request("""{"model":"deepseek-v4-pro","tools":[{"name":"time"},{"name":"weather"}],"messages":[{"role":"user","content":"hello"},{"role":"system","content":"state=INACTIVE"}],"stream":false}""")
        request("""{"model":"deepseek-v4-pro","tools":[{"name":"time"},{"name":"weather"}],"messages":[{"role":"user","content":"hello"},{"role":"assistant","content":"reply"},{"role":"system","content":"state=INACTIVE"}],"stream":true,"stream_options":{"include_usage":true}}""")
        assertTrue(logs.last().contains("sameSystem=true sameTools=true optionDiff=none"))
        assertTrue(logs.last().contains("wireSharedItems=1 previousItems=2 wireFirstDiff=items[1].role"))
        assertTrue(logs.last().contains("historySharedItems=1 previousHistoryItems=1"))
        request("""{"model":"different-model","tools":[{"name":"weather"},{"name":"time"}],"messages":[{"role":"user","content":"hello"}]}""")
        assertTrue(logs.last().contains("sameTools=false optionDiff=model"))
        diagnostics.begin("another-conversation", "Normal", "ChatCompletions", "provider")
            .request(body("system", """{"role":"user","content":"hello"}""", "time"))
        assertTrue(logs.last().contains("compare=previousNormal baseline=missing"))
    }

    private fun body(system: String, history: String, tool: String) = Json.parseToJsonElement(
        """{"model":"deepseek-v4-pro","messages":[{"role":"system","content":"$system"},$history],"tools":[{"type":"function","function":{"name":"$tool","parameters":{}}}]}"""
    ).jsonObject
}
