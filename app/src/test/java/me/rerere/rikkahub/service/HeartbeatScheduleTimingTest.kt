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
}
