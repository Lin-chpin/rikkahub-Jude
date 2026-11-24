package me.rerere.ai.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageCacheReportingTest {
    @Test
    fun `older usage infers a reported cache count only when it is positive`() {
        val oldPositive = Json.decodeFromString<TokenUsage>("""{"cachedTokens":17}""")
        val oldZero = Json.decodeFromString<TokenUsage>("""{"cachedTokens":0}""")
        val missing = Json.decodeFromString<TokenUsage>("{}")

        assertTrue(oldPositive.cachedTokensReported)
        assertFalse(oldZero.cachedTokensReported)
        assertFalse(missing.cachedTokensReported)
    }

    @Test
    fun `explicit zero replaces an earlier cache count and missing chunks keep reported usage`() {
        val previous = TokenUsage(promptTokens = 100, cachedTokens = 60, cachedTokensReported = true)
        val reportedZero = previous.merge(
            TokenUsage(promptTokens = 120, cachedTokens = 0, cachedTokensReported = true),
        )
        val missingLater = previous.merge(TokenUsage(promptTokens = 140))

        assertEquals(0, reportedZero.cachedTokens)
        assertTrue(reportedZero.cachedTokensReported)
        assertEquals(60, missingLater.cachedTokens)
        assertTrue(missingLater.cachedTokensReported)
    }

    @Test
    fun `a later chunk without cache usage does not erase an earlier reported cache count`() {
        val reportedCache = TokenUsage(
            promptTokens = 100,
            cachedTokens = 60,
            cachedTokensReported = true,
        )
        val laterChunk = TokenUsage(promptTokens = 120, completionTokens = 8)

        val merged = reportedCache.merge(laterChunk)

        assertEquals(60, merged.cachedTokens)
        assertTrue(merged.cachedTokensReported)
        assertEquals(120, merged.promptTokens)
        assertEquals(8, merged.completionTokens)
    }
}
