package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIRequestAuthenticatorTest {
    @Test
    fun `API key auth adds bearer token`() = runBlocking {
        val request = OpenAIRequestAuthenticator(KeyRoulette.default())
            .authenticate(
                Request.Builder().url("https://api.openai.com/v1/responses"),
                ProviderSetting.OpenAI(apiKey = "sk-test"),
            )
            .build()

        assertEquals("Bearer sk-test", request.header("Authorization"))
    }

    @Test
    fun `subscription auth uses refreshed token and account headers`() = runBlocking {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL,
            codexCredentials = OpenAICodexCredentials("stale", "refresh", "old-account"),
        )
        val request = OpenAIRequestAuthenticator(KeyRoulette.default()) {
            OpenAICodexCredentials("fresh", "refresh-2", "new-account")
        }.authenticate(
            Request.Builder().url("$OPENAI_CODEX_BASE_URL/responses"),
            setting,
        ).build()

        assertEquals("Bearer fresh", request.header("Authorization"))
        assertEquals("new-account", request.header("ChatGPT-Account-Id"))
        assertEquals("rikkahub", request.header("originator"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subscription auth rejects third party endpoint`() = runBlocking {
        val setting = ProviderSetting.OpenAI(
            authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = "https://gateway.example.com/v1",
            codexCredentials = OpenAICodexCredentials("token", "refresh", "account"),
        )

        OpenAIRequestAuthenticator(KeyRoulette.default()).authenticate(
            Request.Builder().url("https://gateway.example.com/v1/responses"),
            setting,
        )
    }
}
