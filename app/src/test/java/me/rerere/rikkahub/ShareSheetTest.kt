package me.rerere.rikkahub

import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.components.ui.encodeForShare
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareSheetTest {
    @Test
    fun `sharing Codex provider strips subscription credentials`() {
        val original = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            codexCredentials = OpenAICodexCredentials(
                accessToken = "access-secret",
                refreshToken = "refresh-secret",
                accountId = "account-id",
            ),
        )

        val decoded = decodeProviderSetting(original.encodeForShare()) as ProviderSetting.OpenAI

        assertEquals(OpenAIAuthType.CHATGPT_SUBSCRIPTION, decoded.authType)
        assertEquals(null, decoded.codexCredentials)
    }
}
