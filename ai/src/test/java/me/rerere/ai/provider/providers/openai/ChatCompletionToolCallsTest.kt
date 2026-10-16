package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionToolCallsTest {
    @Test
    fun `snapshots replace argument fragments while sparse metadata preserves earlier signature`() {
        val calls = ChatCompletionToolCalls()
        calls.accept(items("""{"index":0,"id":"one","function":{"name":"get_time_info","arguments":"{"},
            "extra_content":{"google":{"thought_signature":"opaque"}}}"""))
        calls.accept(items("""{"id":"one","function":{"name":"get_time_info","arguments":"{}"},
            "extra_content":{"google":{"thought_signature":null,"version":1}}}"""), snapshot = true)
        val tool = calls.finish().single()
        assertEquals("{}", tool.input)
        assertEquals("get_time_info", tool.toolName)
        val replay = tool.toChatToolCall()
        assertEquals(Json.parseToJsonElement("""{"google":{"thought_signature":"opaque","version":1}}"""), replay["extra_content"])
        assertTrue(calls.finish().isEmpty())
    }

    @Test
    fun `malformed parallel calls fail before any tool can execute`() {
        val invalid = listOf(
            """{"function":{"name":"get_time_info","arguments":"{}"}}""",
            """{"index":0,"function":{"name":"get_time_info","arguments":"{}"}}""",
            """{"index":0,"id":"one","function":{"name":"get_time_info","arguments":"{"}}""",
            """{"index":0,"id":"one","function":{"name":"get_time_info","arguments":"[]"}}""",
        )
        invalid.forEach { item ->
            assertThrows(IllegalStateException::class.java) {
                ChatCompletionToolCalls().apply { accept(items(item)) }.finish()
            }
        }
    }

    @Test
    fun `tool merge retains nested protocol metadata without borrowing another protocols signature`() {
        val original = UIMessagePart.Tool("id", "get_time_info", "", metadata = Json.parseToJsonElement("""
            {"openai_chat_tool_call":{"extra_content":{"google":{"thought_signature":"opaque"}}}}
        """).jsonObject)
        val merged = original.merge(UIMessagePart.Tool("id", "", "{}", metadata = Json.parseToJsonElement("""
            {"openai_chat_tool_call":{"extra_content":{"google":{"version":1}}},"thoughtSignature":"native"}
        """).jsonObject))
        assertEquals("opaque", merged.toChatToolCall()["extra_content"]!!.jsonObject["google"]!!
            .jsonObject["thought_signature"]!!.toString().trim('"'))
        assertFalse(merged.toChatToolCall().containsKey("thoughtSignature"))
    }

    private fun items(value: String) = Json.parseToJsonElement("[$value]") as JsonArray
}
