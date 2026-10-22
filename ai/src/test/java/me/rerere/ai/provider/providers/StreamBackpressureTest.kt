package me.rerere.ai.provider.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBackpressureTest {
    @Test
    fun `every provider retains deltas when the collector is slower than SSE`() = runBlocking {
        val texts = (0 until 192).map { "$it," }
        val params = TextGenerationParams(Model(modelId = "test"), suppressContentLogs = true)
        val messages = listOf(UIMessage.user("test"))
        val openAI = ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1")
        val cases: List<Pair<String, suspend (OkHttpClient) -> Flow<MessageChunk>>> = listOf(
            texts.joinToString("") { "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"$it\"}}]}\n\n" } +
                "data: [DONE]\n\n" to { client ->
                    ChatCompletionsAPI(client, KeyRoulette.default()).streamText(openAI, messages, params)
                },
            texts.joinToString("") { "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"reply\",\"delta\":\"$it\"}\n\n" } +
                "data: {\"type\":\"response.completed\",\"response\":{}}\n\n" to { client ->
                    ResponseAPI(client).streamText(openAI, messages, params)
                },
            texts.joinToString("") { "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"$it\"}]}}]}\n\n" } to { client ->
                GoogleProvider(client).streamText(ProviderSetting.Google(apiKey = "test"), messages, params)
            },
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                texts.joinToString("") { "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$it\"}}\n\n" } +
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n" to { client ->
                    ClaudeProvider(client, null).streamText(ProviderSetting.Claude(apiKey = "test"), messages, params)
                },
        )
        for ((sse, stream) in cases) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Content-Type", "text/event-stream")
                    .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            try {
                withTimeout(10_000) {
                    var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
                    stream(client).collect { chunk ->
                        delay(2)
                        message += chunk
                    }
                    assertEquals(texts.joinToString(""), message.toText())
                }
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
