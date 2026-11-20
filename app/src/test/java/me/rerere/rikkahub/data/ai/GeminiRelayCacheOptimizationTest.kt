package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiRelayCacheOptimizationTest {
    @Test
    fun `optimization is limited to enabled OpenAI Chat Completions Gemini models`() {
        val openAI = ProviderSetting.OpenAI()

        assertTrue(shouldOptimizeGeminiRelayCache(true, openAI, Model(modelId = "vendor/GemInI-2.5")))
        assertFalse(shouldOptimizeGeminiRelayCache(false, openAI, Model(modelId = "gemini-2.5")))
        assertFalse(shouldOptimizeGeminiRelayCache(true, openAI, Model(modelId = "gpt-4.1")))
        assertFalse(shouldOptimizeGeminiRelayCache(
            true,
            openAI.copy(useResponseApi = true),
            Model(modelId = "gemini-2.5"),
        ))
        assertFalse(shouldOptimizeGeminiRelayCache(true, ProviderSetting.Google(), Model(modelId = "gemini-2.5")))
        assertFalse(shouldOptimizeGeminiRelayCache(true, ProviderSetting.Claude(), Model(modelId = "gemini-2.5")))
    }

    @Test
    fun `manual session identifiers take precedence and missing session ids are skipped`() {
        val generated = addGeminiRelayCacheSessionId(
            headers = emptyList(),
            body = emptyList(),
            sessionId = "conversation-1",
        )
        assertEquals(CustomHeader("session-id", "conversation-1"), generated.headers.single())
        assertEquals(
            CustomBody("user", JsonPrimitive("conversation-1")),
            generated.body.single(),
        )

        val manual = addGeminiRelayCacheSessionId(
            headers = listOf(CustomHeader("Session-ID", "manual-session")),
            body = listOf(CustomBody("user", JsonPrimitive("manual-user"))),
            sessionId = "conversation-1",
        )
        assertEquals("manual-session", manual.headers.single().value)
        assertEquals(JsonPrimitive("manual-user"), manual.body.single().value)

        val missing = addGeminiRelayCacheSessionId(emptyList(), emptyList(), null)
        assertTrue(missing.headers.isEmpty())
        assertTrue(missing.body.isEmpty())
    }
}
