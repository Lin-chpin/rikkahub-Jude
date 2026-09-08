package me.rerere.rikkahub.service

import me.rerere.rikkahub.personal.heartbeat.HeartbeatScheduleTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatScheduleTimingTest {
    @Test
    fun preservesIntervalFromHeartbeatTriggerInsteadOfCompletion() {
        val startedAt = 1_000_000L
        val finishedAt = startedAt + 30 * 60_000L

        assertEquals(
            startedAt + 60 * 60_000L,
            HeartbeatScheduleTiming.nextRegularTriggerAtMillis(
                nowMillis = finishedAt,
                delayMinutes = 60L,
                anchorAtMillis = startedAt,
                preserveAnchor = true,
            ),
        )
    }

    @Test
    fun longRunDoesNotScheduleInThePast() {
        val now = 10_000_000L

        assertEquals(
            now + 1_000L,
            HeartbeatScheduleTiming.nextRegularTriggerAtMillis(
                nowMillis = now,
                delayMinutes = 60L,
                anchorAtMillis = now - 90 * 60_000L,
                preserveAnchor = true,
            ),
        )
    }

    @Test
    fun normalRescheduleStillWaitsFromNow() {
        val now = 10_000_000L

        assertEquals(
            now + 60 * 60_000L,
            HeartbeatScheduleTiming.nextRegularTriggerAtMillis(
                nowMillis = now,
                delayMinutes = 60L,
                anchorAtMillis = now - 90 * 60_000L,
                preserveAnchor = false,
            ),
        )
    }

    @Test
    fun userActivityRescheduleWaitsFromTheUserMessage() {
        val now = 10_000_000L
        val userMessageAt = now - 5 * 60_000L

        assertEquals(
            userMessageAt + 60 * 60_000L,
            HeartbeatScheduleTiming.nextRegularTriggerAtMillis(
                nowMillis = now,
                delayMinutes = 60L,
                anchorAtMillis = userMessageAt,
                preserveAnchor = true,
            ),
        )
    }

    @Test
    fun failureRetryPrecedesLongRegularInterval() {
        val now = 10_000_000L
        val retryAt = now + 5 * 60_000L
        val regularAt = now + 60 * 60_000L

        assertEquals(
            retryAt,
            HeartbeatScheduleTiming.earliestRetryOrRegularTriggerAtMillis(
                nowMillis = now,
                retryAtMillis = retryAt,
                regularTriggerAtMillis = regularAt,
            ),
        )
    }

    @Test
    fun expiredFailureRetryDoesNotReplaceRegularInterval() {
        val now = 10_000_000L
        val regularAt = now + 60 * 60_000L

        assertEquals(
            regularAt,
            HeartbeatScheduleTiming.earliestRetryOrRegularTriggerAtMillis(
                nowMillis = now,
                retryAtMillis = now - 1L,
                regularTriggerAtMillis = regularAt,
            ),
        )
    }
}
