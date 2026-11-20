package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RequestDiagnosticsCallbackTest {
    @Test
    fun `messages are moved after custom fields only when explicitly requested`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val provider = ProviderSetting.OpenAI()
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(modelId = "vendor/Gemini-2.5"),
            customBody = listOf(CustomBody("vendor_option", JsonPrimitive(true))),
        )

        val regular = api.buildChatCompletionRequest(messages, params, provider)
        val optimized = api.buildChatCompletionRequest(
            messages,
            params.copy(appendMessagesToRequestEnd = true),
            provider,
        )

        assertNotEquals(regular.keys.toList(), optimized.keys.toList())
        assertEquals("messages", optimized.keys.last())
        assertEquals(JsonPrimitive(true), optimized["vendor_option"])
        assertEquals(regular["messages"], optimized["messages"])
    }

    @Test
    fun `diagnostics observe final merged requests even when content logging is suppressed`() {
        val provider = ProviderSetting.OpenAI()
        val messages = listOf(UIMessage.user("private-content"))
        var observed: JsonObject? = null
        val params = TextGenerationParams(
            model = Model(modelId = "model"),
            customBody = listOf(CustomBody("user_id", JsonPrimitive("private-user"))),
            suppressContentLogs = true,
            onRequestPrepared = { observed = it },
            onTokenUsage = {},
        )
        val chat = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            .buildChatCompletionRequest(messages, params, provider)
        assertSame(chat, observed)
        assertEquals(JsonPrimitive("private-user"), observed?.get("user_id"))
        val response = ResponseAPI(OkHttpClient()).buildRequestBody(provider, messages, params, false)
        assertSame(response, observed)
        assertFalse(Json.encodeToString(params).contains("onRequestPrepared"))
        assertFalse(Json.encodeToString(params).contains("onTokenUsage"))
        val failingObserver = params.copy(onRequestPrepared = { error("diagnostic failure") })
        assertEquals(chat, ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            .buildChatCompletionRequest(messages, failingObserver, provider))
    }
}
