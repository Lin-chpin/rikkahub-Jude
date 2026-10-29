package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.common.http.privateFailureStatusCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GenerationRequestReplayTest {
    @Test
    fun `all four protocols mark streaming and nonstream text requests as non replayable`() = runBlocking {
        val params = TextGenerationParams(Model(modelId = "test"), suppressContentLogs = true)
        val messages = listOf(UIMessage.user("test"))
        val openAI = ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1")
        val google = ProviderSetting.Google(apiKey = "test")
        val claude = ProviderSetting.Claude(apiKey = "test")
        val cases: List<suspend (OkHttpClient) -> Unit> = listOf(
            { ChatCompletionsAPI(it, KeyRoulette.default()).generateText(openAI, messages, params) },
            { ChatCompletionsAPI(it, KeyRoulette.default()).streamText(openAI, messages, params).collect {} },
            { ResponseAPI(it).generateText(openAI, messages, params) },
            { ResponseAPI(it).streamText(openAI, messages, params).collect {} },
            { GoogleProvider(it).generateText(google, messages, params) },
            { GoogleProvider(it).streamText(google, messages, params).collect {} },
            { ClaudeProvider(it).generateText(claude, messages, params) },
            { ClaudeProvider(it).streamText(claude, messages, params).collect {} },
        )
        for (request in cases) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                assertTrue(chain.request().body!!.isOneShot())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(429).message("Too Many Requests").header("Content-Type", "application/json")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                val failure = runCatching { withTimeout(5000) { request(client) } }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(429, privateFailureStatusCode(failure!!))
                assertEquals(1, requests)
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
