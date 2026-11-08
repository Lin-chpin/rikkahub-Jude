package me.rerere.rikkahub.service

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.CompressOpenAIConfig
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CompressionTextGeneratorTest {
    @Test
    fun `shared prompt renderer applies the current compression template`() {
        val settings = Settings.dummy().copy(
            compressPrompt = "{locale}|{target_tokens}|{additional_context}|{content}",
        )

        assertEquals(
            "zh-CN|700|preserve decisions|conversation text",
            renderCompressionPrompt(
                settings = settings,
                content = "conversation text",
                targetTokens = 700,
                additionalContext = "preserve decisions",
                locale = "zh-CN",
            ),
        )
    }

    @Test
    fun `shared compression model and API overrides honor settings`() {
        val model = Model(modelId = "original-model")
        val provider = ProviderSetting.OpenAI(apiKey = "original-key")
        val disabled = CompressOpenAIConfig()
        val enabled = CompressOpenAIConfig(
            enabled = true,
            modelId = " replacement-model ",
            apiKey = "replacement-key",
            baseUrl = "https://compression.example/v1",
            chatCompletionsPath = "/chat/completions",
            useResponseApi = true,
        )

        assertSame(model, model.withCompressionModelOverride(disabled))
        assertSame(provider, provider.withCompressionApiOverride(disabled))
        assertEquals("replacement-model", model.withCompressionModelOverride(enabled).modelId)
        val overriddenProvider = provider.withCompressionApiOverride(enabled) as ProviderSetting.OpenAI
        assertEquals("replacement-key", overriddenProvider.apiKey)
        assertEquals("https://compression.example/v1", overriddenProvider.baseUrl)
        assertEquals("/chat/completions", overriddenProvider.chatCompletionsPath)
        assertEquals(true, overriddenProvider.useResponseApi)
    }
}
