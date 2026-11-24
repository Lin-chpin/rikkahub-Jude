package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.ai.gemini.GeminiOAuthQuotaBucket
import me.rerere.rikkahub.data.ai.gemini.GeminiOAuthQuotaGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubscriptionQuotaButtonTest {
    @Test
    fun `differentiates 5h and weekly buckets sharing the same display name`() {
        val groups = listOf(
            GeminiOAuthQuotaGroup(
                id = "gemini",
                displayName = "Gemini",
                buckets = listOf(
                    GeminiOAuthQuotaBucket(
                        id = "gemini-5h",
                        displayName = "Gemini 3 Flash",
                        window = "5h",
                        remainingFraction = 0.42,
                        resetTime = "2026-11-24T05:00:00Z",
                    ),
                    GeminiOAuthQuotaBucket(
                        id = "gemini-weekly",
                        displayName = "Gemini 3 Flash",
                        window = "weekly",
                        remainingFraction = 0.88,
                        resetTime = "2026-11-30T00:00:00Z",
                    ),
                ),
            )
        )

        val rows = groups.toQuotaRows()

        assertEquals(2, rows.size)
        assertNotEquals(rows[0].title, rows[1].title)
        assertEquals("Gemini · Gemini 3 Flash (5h)", rows[0].title)
        assertEquals("Gemini · Gemini 3 Flash (weekly)", rows[1].title)
        assertEquals(0.42f, rows[0].remainingFraction!!, 0.0001f)
        assertEquals(0.88f, rows[1].remainingFraction!!, 0.0001f)
        assertEquals("2026-11-24 13:00:00", rows[0].resetTime)
        assertEquals("2026-11-30 08:00:00", rows[1].resetTime)
    }

    @Test
    fun `avoids duplicating group name when bucket display name matches group`() {
        val fiveHour = GeminiOAuthQuotaBucket(
            id = "gemini-5h",
            displayName = "Gemini",
            window = "5h",
            remainingFraction = 0.5,
            resetTime = null,
        )
        val weekly = GeminiOAuthQuotaBucket(
            id = "gemini-weekly",
            displayName = "Gemini",
            window = "weekly",
            remainingFraction = 0.9,
            resetTime = null,
        )

        assertEquals("Gemini (5h)", formatGeminiQuotaRowTitle("Gemini", fiveHour))
        assertEquals("Gemini (weekly)", formatGeminiQuotaRowTitle("Gemini", weekly))
        assertEquals("Gemini (5h)", formatGeminiQuotaRowTitle("", fiveHour))
        assertEquals("Gemini (weekly)", formatGeminiQuotaRowTitle("", weekly))
    }

    @Test
    fun `does not append redundant window suffix when display name already mentions window`() {
        val fiveHour = GeminiOAuthQuotaBucket(
            id = "gemini-5h",
            displayName = "Five Hour Limit Remaining",
            window = "5h",
            remainingFraction = 0.7,
            resetTime = null,
        )
        val weekly = GeminiOAuthQuotaBucket(
            id = "gemini-weekly",
            displayName = "Weekly Limit Remaining",
            window = "weekly",
            remainingFraction = 0.8,
            resetTime = null,
        )

        assertEquals("Gemini · Five Hour Limit Remaining", formatGeminiQuotaRowTitle("Gemini", fiveHour))
        assertEquals("Gemini · Weekly Limit Remaining", formatGeminiQuotaRowTitle("Gemini", weekly))
    }

    @Test
    fun `omits window suffix when bucket window is null`() {
        val modelBucket = GeminiOAuthQuotaBucket(
            id = "gemini-3-flash",
            displayName = "Gemini 3 Flash",
            window = null,
            remainingFraction = 0.6,
            resetTime = null,
        )

        assertEquals("Gemini Models · Gemini 3 Flash", formatGeminiQuotaRowTitle("Gemini Models", modelBucket))
    }
}
