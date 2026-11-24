package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIProviderModelsTest {
    @Test
    fun `Codex model URL includes client version`() {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL,
        )

        val url = openAIModelsUrl(setting)

        assertEquals("$OPENAI_CODEX_BASE_URL/models", url.toString().substringBefore('?'))
        assertEquals("0.166.0", url.queryParameter("client_version"))
    }

    @Test
    fun `API key model URL has no Codex query parameter`() {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.API_KEY,
            baseUrl = "https://api.openai.com/v1",
        )

        val url = openAIModelsUrl(setting)

        assertEquals("https://api.openai.com/v1/models", url.toString())
        assertNull(url.queryParameter("client_version"))
    }

    @Test
    fun `Codex model response filters hidden and unsupported models`() {
        val models = parseOpenAIModels(
            """
            {"models":[
              {"slug":"gpt-5.6-sol","display_name":"GPT-5.6 Sol","supported_in_api":true,"visibility":"list"},
              {"slug":"hidden","visibility":"hide"},
              {"slug":"unsupported","supported_in_api":false}
            ]}
            """.trimIndent()
        )

        assertEquals(1, models.size)
        assertEquals("gpt-5.6-sol", models.single().modelId)
        assertEquals("GPT-5.6 Sol", models.single().displayName)
    }

    @Test
    fun `Codex Responses image models expose image generation capability`() {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL,
            useResponseApi = true,
        )

        val model = Model(modelId = "gpt-6.2-astra")
            .withCodexImageGenerationCapability(setting)

        assertTrue(BuiltInTools.ImageGeneration in model.tools)
    }

    @Test
    fun `Codex image generation capability requires subscription Responses and a supported model`() {
        val apiSetting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.API_KEY,
            useResponseApi = true,
        )
        val codexChatCompletionsSetting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL,
            useResponseApi = false,
        )
        val unsupportedModel = Model(modelId = "gpt-6.3-astra")

        assertFalse(BuiltInTools.ImageGeneration in Model(modelId = "gpt-6.2-astra")
            .withCodexImageGenerationCapability(apiSetting).tools)
        assertFalse(BuiltInTools.ImageGeneration in Model(modelId = "gpt-6.2-astra")
            .withCodexImageGenerationCapability(codexChatCompletionsSetting).tools)
        assertFalse(BuiltInTools.ImageGeneration in unsupportedModel
            .withCodexImageGenerationCapability(
                ProviderSetting.OpenAI(
                    authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
                    baseUrl = OPENAI_CODEX_BASE_URL,
                    useResponseApi = true,
                )
            ).tools)
    }
}
