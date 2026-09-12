package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigureConvertToTest {
    @Test
    fun `ChatGPT subscription is offered only for official OpenAI endpoints`() {
        assertTrue(ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1").supportsChatGPTSubscription())
        assertTrue(ProviderSetting.OpenAI(baseUrl = OPENAI_CODEX_BASE_URL).supportsChatGPTSubscription())
        assertFalse(ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1").supportsChatGPTSubscription())
        assertFalse(ProviderSetting.OpenAI(baseUrl = "not-a-url").supportsChatGPTSubscription())
    }

    @Test
    fun `Codex endpoint is treated as official during provider conversion`() {
        val converted = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL,
        ).convertTo(ProviderSetting.Google::class) as ProviderSetting.Google

        assertEquals("https://generativelanguage.googleapis.com/v1beta", converted.baseUrl)
    }
}
