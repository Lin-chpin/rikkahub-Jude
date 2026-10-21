package me.rerere.ai.provider.providers

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GoogleProviderReasoningTest {
    @Test
    fun `model list keeps Gemini relay entries with missing generation methods`() {
        val models = parseGoogleModelList(Json.parseToJsonElement(
            """{"models":[
                {"name":"models/gemini-3.8-flash","displayName":"Gemini 3.8 Flash","supportedGenerationMethods":["generateContent"]},
                {"name":"[relay]gemini-3.8-flash","displayName":"Relay Gemini","supportedGenerationMethods":null},
                {"name":"claude-sonnet","displayName":"Claude","supportedGenerationMethods":null},
                {"name":"gemini-embedding-001","displayName":"Gemini Embedding","supportedGenerationMethods":null},
                {"name":"embed-only","displayName":"Embedding","supportedGenerationMethods":["embedContent"]}
            ]}"""
        ).jsonObject)

        assertEquals(listOf("gemini-3.8-flash", "[relay]gemini-3.8-flash", "embed-only"), models.map { it.modelId })
        assertEquals(listOf(ModelType.CHAT, ModelType.CHAT, ModelType.EMBEDDING), models.map { it.type })
    }

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
