package me.rerere.rikkahub.data.ai.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiOAuthAccountTest {
    @Test
    fun `only the access token that received unauthorized can invalidate the stored account`() {
        val current = GeminiStoredAccount(
            email = "user@example.com",
            projectId = "project-current",
            accessToken = "current-token",
            refreshToken = "refresh-token",
            expiresAt = 1_000L,
        )

        assertEquals(
            current.copy(tokenStatus = GeminiOAuthTokenStatus.INVALID),
            current.invalidateIfAccessTokenMatches("current-token"),
        )
        assertNull(current.invalidateIfAccessTokenMatches("previous-token"))
    }
}
