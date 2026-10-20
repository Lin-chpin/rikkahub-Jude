package me.rerere.rikkahub.personal.heartbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatConfigTest {
    @Test
    fun sentinelMayBeShorterButCannotExceedHeartbeatIntervals() {
        val shorter = HeartbeatConfig(
            enabled = true,
            minIntervalMinutes = 5,
            maxIntervalMinutes = 20,
            sentinel = SentinelConfig(enabled = true, minIntervalMinutes = 1, maxIntervalMinutes = 4),
        ).normalized()

        assertTrue(shorter.sentinel.enabled)
        assertEquals(1, shorter.sentinel.minIntervalMinutes)

        val longer = HeartbeatConfig(
            enabled = true,
            minIntervalMinutes = 5,
            maxIntervalMinutes = 10,
            sentinel = SentinelConfig(enabled = true, minIntervalMinutes = 4, maxIntervalMinutes = 11),
        )

        assertTrue(longer.sentinelIntervalsExceedHeartbeat())
        assertFalse(longer.normalized().sentinel.enabled)
    }
}
