package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderResponseParseException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFailurePropagationTest {
    @Test
    fun `Google malformed stream event fails as response parsing after preserving prior chunks`() = runBlocking {
        val client = streamingClient(
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"partial"}]}}]}""" +
                "\n\ndata: not-json\n\n"
        )
        var chunks = 0
        try {
            val failure = runCatching {
                GoogleProvider(client).streamText(
                    ProviderSetting.Google(apiKey = "test"),
                    listOf(UIMessage.user("hello")),
                    TextGenerationParams(Model(modelId = "test")),
                ).collect { chunks++ }
            }.exceptionOrNull()

            assertEquals(1, chunks)
            assertTrue(failure is ProviderResponseParseException)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `Responses failure event preserves provider code and type`() = runBlocking {
        val client = streamingClient(
            """data: {"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded","type":"rate_limit_error","message":"slow down"}}}""" +
                "\n\n"
        )
        try {
            val failure = runCatching {
                ResponseAPI(client).streamText(
                    ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1"),
                    listOf(UIMessage.user("hello")),
                    TextGenerationParams(Model(modelId = "test")),
                ).collect()
            }.exceptionOrNull()

            assertTrue(failure is HttpException)
            assertEquals("Provider reported an error", (failure as HttpException).message)
            assertEquals("rate_limit_exceeded", (failure as HttpException).providerCode)
            assertEquals("rate_limit_error", failure.providerType)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `Google HTTP failure preserves status and structured provider fields`() = runBlocking {
        val client = streamingClient(
            """{"error":{"code":"rate_limit_exceeded","type":"rate_limit_error","message":"slow down"}}""",
            statusCode = 429,
        )
        try {
            val failure = runCatching {
                GoogleProvider(client).streamText(
                    ProviderSetting.Google(apiKey = "test"),
                    listOf(UIMessage.user("hello")),
                    TextGenerationParams(Model(modelId = "test")),
                ).collect()
            }.exceptionOrNull()

            assertTrue(failure is HttpException)
            assertEquals(429, (failure as HttpException).statusCode)
            assertEquals("Provider reported an error", (failure as HttpException).message)
            assertEquals("rate_limit_exceeded", failure.providerCode)
            assertEquals("rate_limit_error", failure.providerType)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun streamingClient(body: String, statusCode: Int = 200): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(statusCode).message(if (statusCode == 200) "OK" else "Error")
                .header("Content-Type", "text/event-stream")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
}
