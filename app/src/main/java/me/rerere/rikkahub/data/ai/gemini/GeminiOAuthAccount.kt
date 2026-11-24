package me.rerere.rikkahub.data.ai.gemini

import kotlinx.serialization.Serializable

@Serializable
internal data class GeminiStoredAccount(
    val email: String,
    val projectId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val tokenStatus: GeminiOAuthTokenStatus = GeminiOAuthTokenStatus.AVAILABLE,
)

@Serializable
internal enum class GeminiOAuthTokenStatus {
    AVAILABLE,
    INVALID,
}

data class GeminiOAuthAccountSummary(
    val email: String,
    val needsReauthentication: Boolean = false,
)

internal fun GeminiStoredAccount.toSummary() = GeminiOAuthAccountSummary(
    email = email.ifBlank { "Google account" },
    needsReauthentication = tokenStatus == GeminiOAuthTokenStatus.INVALID,
)

internal fun GeminiStoredAccount.invalidateIfAccessTokenMatches(accessToken: String): GeminiStoredAccount? =
    takeIf { this.accessToken == accessToken }
        ?.copy(tokenStatus = GeminiOAuthTokenStatus.INVALID)
