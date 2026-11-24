package me.rerere.ai.provider.providers

data class GeminiOAuthCredentials(
    val accessToken: String,
    val projectId: String,
)

interface GeminiOAuthTokenProvider {
    suspend fun getCredentials(): GeminiOAuthCredentials

    suspend fun markUnauthorized(accessToken: String)
}
