package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

fun interface OpenAICodexTokenProvider {
    suspend fun getCredentials(providerSetting: ProviderSetting.OpenAI): OpenAICodexCredentials
}

internal class OpenAIRequestAuthenticator(
    private val keyRoulette: KeyRoulette,
    private val codexTokenProvider: OpenAICodexTokenProvider? = null,
) {
    suspend fun authenticate(
        builder: Request.Builder,
        providerSetting: ProviderSetting.OpenAI,
    ): Request.Builder {
        return when (providerSetting.authType) {
            OpenAIAuthType.API_KEY -> {
                val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
                builder.header("Authorization", "Bearer $key")
            }

            OpenAIAuthType.CHATGPT_SUBSCRIPTION -> {
                val configuredHost = providerSetting.baseUrl.toHttpUrlOrNull()?.host
                val requestHost = builder.build().url.host
                val codexHost = OPENAI_CODEX_BASE_URL.toHttpUrl().host
                require(configuredHost == codexHost && requestHost == codexHost) {
                    "ChatGPT subscription authentication is only available for the official OpenAI Codex endpoint."
                }
                val credentials = codexTokenProvider?.getCredentials(providerSetting)
                    ?: providerSetting.codexCredentials
                    ?: error("OpenAI Codex is not signed in. Sign in with ChatGPT first.")
                require(credentials.accessToken.isNotBlank()) {
                    "OpenAI Codex access token is empty. Sign in with ChatGPT again."
                }
                require(credentials.accountId.isNotBlank()) {
                    "OpenAI Codex account is missing. Sign in with ChatGPT again."
                }
                builder
                    .header("Authorization", "Bearer ${credentials.accessToken}")
                    .header("ChatGPT-Account-Id", credentials.accountId)
                    .header("originator", "rikkahub")
            }
        }
    }
}
