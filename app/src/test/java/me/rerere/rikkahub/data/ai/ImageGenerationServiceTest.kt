package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationServiceTest {
    @Test
    fun `Codex image route adds Responses image tool to a request copy`() {
        val savedModel = Model(modelId = "gpt-6.2-astra")
        val codex = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL,
            useResponseApi = true,
        )

        val requestModel = resolveImageGenerationModel(savedModel, codex)

        assertTrue(BuiltInTools.ImageGeneration in requestModel.tools)
        assertFalse(BuiltInTools.ImageGeneration in savedModel.tools)
    }

    @Test
    fun `API key route does not gain Codex image tool metadata`() {
        val savedModel = Model(modelId = "gpt-6.2-astra")
        val apiKey = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.API_KEY,
            useResponseApi = true,
        )

        val requestModel = resolveImageGenerationModel(savedModel, apiKey)

        assertFalse(BuiltInTools.ImageGeneration in requestModel.tools)
    }
}
