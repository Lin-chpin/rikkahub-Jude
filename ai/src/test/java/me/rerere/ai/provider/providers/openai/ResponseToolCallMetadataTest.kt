package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResponseToolCallMetadataTest {
    @Test
    fun `streamed parallel calls retain signatures through aggregation persistence and execution`() = runBlocking {
        val events = ResponseToolCallEvents()
        events.accept(event("added", signedCall))
        events.accept(event("added", plainCall))
        // A later sparse snapshot must not erase the signature received on the first snapshot.
        events.accept(event("done", signedCallWithoutSignature))
        val aggregated = collectStreamingTextGeneration(Model(modelId = "test"), flowOf(events.finish()!!))
            .choices.single().message!!
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(aggregated))
        val completed = restored.copy(parts = restored.getTools().map {
            it.copy(output = listOf(UIMessagePart.Text("result")))
        })
        val input = ResponseAPI(OkHttpClient()).buildMessages(listOf(completed)).map { it.jsonObject }

        val first = input[0]
        listOf("id", "thought_signature", "thoughtSignature", "extra_content").forEach { key ->
            assertEquals(key, signedCall[key], first[key])
        }
        assertEquals(plainCall["id"], input[2]["id"])
        assertFalse(input[2].containsKey("thought_signature"))
        assertFalse(input[2].containsKey("extra_content"))
        listOf(input[1], input[3]).forEach { output ->
            assertFalse(output.containsKey("thought_signature"))
            assertFalse(output.containsKey("extra_content"))
        }
    }

    @Test
    fun `completed event can supply late signature without losing earlier extensions`() {
        val events = ResponseToolCallEvents()
        events.accept(event("added", signedCallWithoutSignature))
        events.accept(Json.parseToJsonElement("""
            {"type":"response.completed","response":{"output":[$signedCall]}}
        """).jsonObject)
        val replay = events.finish()!!.choices.single().delta!!.getTools().single().toResponseFunctionCall()
        assertEquals(signedCall["extra_content"], replay["extra_content"])
        assertEquals(signedCall["thought_signature"], replay["thought_signature"])
    }

    @Test
    fun `non streaming response preserves the same call fields`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"id":"resp_test","model":"test","output":[$signedCall]}""".toResponseBody())
                .build()
        }.build()
        try {
            val api = ResponseAPI(client)
            val message = api.generateText(
                ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1"),
                listOf(UIMessage.user("test")),
                TextGenerationParams(Model(modelId = "test")),
            ).choices.single().message!!
            val completed = message.copy(parts = message.getTools().map {
                it.copy(output = listOf(UIMessagePart.Text("result")))
            })
            val replay = api.buildMessages(listOf(completed)).first().jsonObject
            listOf("id", "thought_signature", "thoughtSignature", "extra_content").forEach { key ->
                assertEquals(key, signedCall[key], replay[key])
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `unrelated metadata and unsigned legacy calls do not acquire signatures`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "old_call", toolName = "weather", input = "{}",
            output = listOf(UIMessagePart.Text("result")),
            metadata = buildJsonObject { put("thoughtSignature", "another protocol") },
        )
        val replay = ResponseAPI(OkHttpClient()).buildMessages(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
        ).first().jsonObject
        assertEquals(setOf("type", "call_id", "name", "arguments"), replay.keys)
    }

    private fun event(phase: String, call: JsonObject) = buildJsonObject {
        put("type", "response.output_item.$phase")
        put("item", call)
    }

    private val signedCall = Json.parseToJsonElement("""
        {"type":"function_call","id":"fc_signed","call_id":"call_signed","name":"weather","arguments":"{}",
         "thought_signature":"opaque+/=","thoughtSignature":"camel+/=",
         "extra_content":{"google":{"thought_signature":"nested+/="},"other":{"value":1}}}
    """).jsonObject

    private val signedCallWithoutSignature = Json.parseToJsonElement("""
        {"type":"function_call","id":"fc_signed","call_id":"call_signed","name":"weather","arguments":"{}",
         "extra_content":{"google":{}}}
    """).jsonObject

    private val plainCall = Json.parseToJsonElement("""
        {"type":"function_call","id":"fc_plain","call_id":"call_plain","name":"weather","arguments":"{}"}
    """).jsonObject
}
