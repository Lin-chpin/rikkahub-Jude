package me.rerere.ai.provider.providers

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GoogleProviderReasoningTest {
    @Test
    fun `Gemini 3_8 uses supported thinking levels even with an old saved model`() {
        val provider = GoogleProvider(OkHttpClient())
        for ((level, expected) in mapOf(
            ReasoningLevel.OFF to "low",
            ReasoningLevel.AUTO to null,
            ReasoningLevel.LOW to "low",
            ReasoningLevel.MEDIUM to "medium",
            ReasoningLevel.HIGH to "high",
            ReasoningLevel.XHIGH to "high",
        )) {
            val body = provider.buildCompletionRequestBody(
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(Model(modelId = "gemini-3.8-flash"), reasoningLevel = level),
            )
            val config = body.getValue("generationConfig").jsonObject.getValue("thinkingConfig").jsonObject
            assertEquals(level.name, expected, config["thinkingLevel"]?.jsonPrimitive?.content)
            assertFalse(config.containsKey("thinkingBudget"))
        }

        val olderModelBody = provider.buildCompletionRequestBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(Model(modelId = "gemini-3-flash"), reasoningLevel = ReasoningLevel.OFF),
        )
        assertEquals(
            "minimal",
            olderModelBody.getValue("generationConfig").jsonObject
                .getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content,
        )
    }
}
