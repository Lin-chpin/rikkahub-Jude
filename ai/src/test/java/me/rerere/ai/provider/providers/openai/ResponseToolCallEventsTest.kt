package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseToolCallEventsTest {
    @Test
    fun `missing done arguments uses deltas and actual call id survives result serialization`() {
        val events = ResponseToolCallEvents()
        events.accept(item("added"))
        events.accept(arguments("delta", "delta", "{\"city\":"))
        events.accept(arguments("delta", "delta", "\"Paris\"}"))
        events.accept(arguments("done"))
        val message = events.finish()!!.choices.single().delta!!
        val tool = message.getTools().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals("weather", tool.toolName)
        assertEquals("{\"city\":\"Paris\"}", tool.input)
        assertNull(events.finish())

        val input = ResponseAPI(OkHttpClient()).buildMessages(
            listOf(message.copy(parts = listOf(tool.copy(output = listOf(UIMessagePart.Text("sunny"))))))
        )
        assertEquals(listOf("function_call", "function_call_output"), input.map {
            it.jsonObject["type"]!!.jsonPrimitive.content
        })
        input.forEach { assertEquals("call_1", it.jsonObject["call_id"]!!.jsonPrimitive.content) }
    }

    @Test
    fun `final snapshots replace fragments once and parallel tools stay separate`() {
        val events = ResponseToolCallEvents()
        events.accept(item("added"))
        events.accept(item("added", id = "fc_2", callId = "call_2"))
        events.accept(arguments("delta", "delta", "{\"city\":"))
        events.accept(arguments("delta", "delta", "{}", id = "fc_2"))
        events.accept(arguments("done", "arguments", "{\"city\":\"Paris\"}"))
        events.accept(item("done", input = "{\"city\":\"Paris\"}"))
        events.accept(arguments("done", id = "fc_2"))
        val tools = events.finish()!!.choices.single().delta!!.getTools()
        assertEquals(listOf("call_1", "call_2"), tools.map { it.toolCallId })
        assertEquals(listOf("{\"city\":\"Paris\"}", "{}"), tools.map { it.input })
    }

    @Test
    fun `item and completed snapshots recover missing parameter events`() {
        val itemOnly = ResponseToolCallEvents()
        itemOnly.accept(item("done", input = "{}"))
        assertEquals("{}", itemOnly.finish()!!.choices.single().delta!!.getTools().single().input)

        val completedOnly = ResponseToolCallEvents()
        completedOnly.accept(Json.parseToJsonElement("""
            {"type":"response.completed","response":{"output":[
              {"type":"function_call","id":"fc_1","call_id":"call_1","name":"weather","arguments":"{}"}
            ]}}
        """).jsonObject)
        assertEquals("call_1", completedOnly.finish()!!.choices.single().delta!!.getTools().single().toolCallId)
        assertNull(ResponseToolCallEvents().finish())
    }

    @Test
    fun `missing or truncated arguments and missing call id fail without fabricating input`() {
        listOf("", "{\"city\":", "null").forEach { input ->
            val events = ResponseToolCallEvents()
            events.accept(item("added", input = input))
            events.accept(arguments("done"))
            assertThrows(IllegalStateException::class.java) { events.finish() }
        }
        val events = ResponseToolCallEvents()
        events.accept(item("done", callId = null, input = "{}"))
        assertThrows(IllegalStateException::class.java) { events.finish() }
    }

    private fun item(
        phase: String,
        id: String = "fc_1",
        callId: String? = "call_1",
        input: String = "",
    ): JsonObject = buildJsonObject {
        put("type", "response.output_item.$phase")
        put("item", buildJsonObject {
            put("type", "function_call")
            put("id", id)
            callId?.let { put("call_id", it) }
            put("name", "weather")
            put("arguments", input)
        })
    }

    private fun arguments(
        phase: String,
        field: String? = null,
        value: String = "",
        id: String = "fc_1",
    ): JsonObject = buildJsonObject {
        put("type", "response.function_call_arguments.$phase")
        put("item_id", id)
        field?.let { put(it, value) }
    }
}
