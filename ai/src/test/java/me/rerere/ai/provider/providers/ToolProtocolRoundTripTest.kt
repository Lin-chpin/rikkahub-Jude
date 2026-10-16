package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ToolProtocolRoundTripTest {
    private val params = TextGenerationParams(Model(modelId = "test"))
    private val openAI = ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1")

    @Test
    fun `chat stream isolates parallel calls and retains late nested signature`() = runBlocking {
        val deltas = listOf(
            """{"tool_calls":[{"index":0,"id":"usage","type":"function","function":{"name":"get_device_usage_stats","arguments":"{\"days\":"},"extra_content":{"google":{"thought_signature":"signed+/="}}},{"index":1,"id":"time","type":"function","function":{"name":"get_time_info","arguments":"{"}}]}""",
            """{"tool_calls":[{"index":1,"function":{"arguments":"}"}},{"index":0,"function":{"arguments":"7}","thought_signature":"function+/="},"extra_content":{"google":{"version":1}}}]}""",
        )
        val sse = deltas.joinToString("") { "data: {\"choices\":[{\"delta\":$it}]}\n\n" } +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
        withClient(sse, "text/event-stream") { client ->
            val api = ChatCompletionsAPI(client, KeyRoulette.default())
            val message = collect(api.streamText(openAI, listOf(UIMessage.user("test")), params))
            assertEquals(listOf("get_device_usage_stats", "get_time_info"), message.getTools().map { it.toolName })
            assertEquals(listOf("{\"days\":7}", "{}"), message.getTools().map { it.input })
            val replay = api.buildChatCompletionRequest(listOf(completed(message)), params, openAI)
                .getValue("messages").jsonArray
            val calls = replay[0].jsonObject.getValue("tool_calls").jsonArray
            assertEquals("signed+/=", calls[0].jsonObject["extra_content"]!!.jsonObject["google"]!!
                .jsonObject["thought_signature"]!!.jsonPrimitive.content)
            assertEquals("1", calls[0].jsonObject["extra_content"]!!.jsonObject["google"]!!
                .jsonObject["version"]!!.jsonPrimitive.content)
            assertEquals("function+/=", calls[0].jsonObject["function"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
            assertFalse(calls[1].jsonObject.containsKey("extra_content"))
            assertEquals("usage", replay[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("time", replay[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `all registered local tool names use the same signed chat and Responses round trip`() = runBlocking {
        val names = listOf("eval_javascript", "get_time_info", "clipboard_tool", "text_to_speech", "ask_user",
            "get_device_usage_stats", "request_voice_call", "usage_lock_control", "get_local_weather",
            "post_moment", "delete_moment", "post_anonymous_question", "delete_anonymous_question",
            "memory_tool", "search_web", "scrape_web", "use_skill", "heartbeat_schedule", "select_voice_call_audio_tags",
            "sentinel_observe")
        val chatCalls = names.mapIndexed { index, name -> obj("""
            {"id":"call_$index","type":"function","function":{"name":"$name","arguments":"{}"},
             "extra_content":{"google":{"thought_signature":"sig_$index"}}}
        """) }
        val body = """{"choices":[{"message":{"role":"assistant","tool_calls":${JsonArray(chatCalls)}},"finish_reason":"tool_calls"}]}"""
        withClient(body) { client ->
            val api = ChatCompletionsAPI(client, KeyRoulette.default())
            val message = api.generateText(openAI, listOf(UIMessage.user("test")), params).choices.single().message!!
            val calls = api.buildChatCompletionRequest(listOf(completed(message)), params, openAI)
                .getValue("messages").jsonArray[0].jsonObject.getValue("tool_calls").jsonArray
            assertEquals(chatCalls, calls)
        }
        val responseCalls = names.mapIndexed { index, name -> obj("""
            {"id":"fc_$index","call_id":"call_$index","type":"function_call","name":"$name","arguments":"{}",
             "extra_content":{"google":{"thought_signature":"sig_$index"}}}
        """) }
        withClient("""{"output":${JsonArray(responseCalls)}}""") { client ->
            val api = ResponseAPI(client)
            val message = api.generateText(openAI, listOf(UIMessage.user("test")), params).choices.single().message!!
            val input = api.buildMessages(listOf(completed(message)))
            assertEquals(responseCalls, input.filterIndexed { index, _ -> index % 2 == 0 })
        }
    }

    @Test
    fun `Google preserves signed text boundaries thinking and original call IDs`() = runBlocking {
        val parts = """[
            {"text":"first","thoughtSignature":"text_A"},
            {"text":"second","thoughtSignature":"text_B"},
            {"text":"thinking","thought":true,"thoughtSignature":"reasoning"},
            {"functionCall":{"id":"native_A","name":"get_time_info","args":{}},"thoughtSignature":"tool"},
            {"functionCall":{"id":"native_B","name":"get_time_info","args":{}}}
        ]"""
        withClient("""{"candidates":[{"content":{"role":"model","parts":$parts}}],"usageMetadata":{}}""") { client ->
            val api = GoogleProvider(client)
            val original = api.generateText(ProviderSetting.Google(apiKey = "test"), listOf(UIMessage.user("test")), params)
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()) + original
            val contents = api.buildCompletionRequestBody(listOf(completed(message)), params)["contents"]!!.jsonArray
            assertEquals(Json.parseToJsonElement(parts), contents[0].jsonObject["parts"])
            val results = contents[1].jsonObject["parts"]!!.jsonArray
            assertEquals(listOf("native_A", "native_B"), results.map {
                it.jsonObject["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content
            })
        }
    }

    @Test
    fun `Claude joins signature fragments preserves redacted block and pairs indexed tools`() = runBlocking {
        val events = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"first+/"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"second="}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"one","name":"get_time_info","input":{}}}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"two","name":"get_local_weather","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            """{"type":"message_stop"}""",
        )
        var replay: JsonObject? = null
        val sse = events.joinToString("") { "event: ${obj(it)["type"]!!.jsonPrimitive.content}\ndata: $it\n\n" }
        withClient(sse, "text/event-stream", { replay = it }) { client ->
            val api = ClaudeProvider(client, null)
            val setting = ProviderSetting.Claude(apiKey = "test", baseUrl = "https://example.invalid/v1")
            val message = collect(api.streamText(setting, listOf(UIMessage.user("test")), params))
            assertEquals(listOf("{}", "{}"), message.getTools().map { it.input })
            collect(api.streamText(setting, listOf(completed(message)), params))
            val messages = replay!!["messages"]!!.jsonArray
            val blocks = messages[0].jsonObject["content"]!!.jsonArray
            assertEquals(obj("""{"type":"thinking","thinking":"think","signature":"first+/second="}"""), blocks[0])
            assertEquals(obj("""{"type":"redacted_thinking","data":"opaque"}"""), blocks[1])
            assertEquals(listOf("one", "two"), messages[1].jsonObject["content"]!!.jsonArray.map {
                it.jsonObject["tool_use_id"]!!.jsonPrimitive.content
            })
        }
    }

    private suspend fun collect(flow: Flow<MessageChunk>): UIMessage = withTimeout(5000) {
        var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        flow.collect { message += it }
        message
    }

    private fun completed(message: UIMessage): UIMessage {
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(message))
        return restored.copy(parts = restored.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(approvalState = ToolApprovalState.Pending)
                .copy(approvalState = ToolApprovalState.Approved, output = listOf(UIMessagePart.Text("result"))) else part
        })
    }

    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject

    private suspend fun withClient(
        response: String,
        contentType: String = "application/json",
        observeRequest: (JsonObject) -> Unit = {},
        block: suspend (OkHttpClient) -> Unit,
    ) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            observeRequest(obj(buffer.readUtf8()))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", contentType)
                .body(response.toResponseBody(contentType.toMediaType())).build()
        }.build()
        try { block(client) } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
