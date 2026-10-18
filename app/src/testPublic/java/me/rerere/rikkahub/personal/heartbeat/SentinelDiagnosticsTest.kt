package me.rerere.rikkahub.personal.heartbeat

import org.junit.Assert.*
import org.junit.Test

class SentinelDiagnosticsTest {
    @Test fun `skipped observation is not a success or infrastructure failure`() {
        val success = SentinelDiagnostics().begin(1000, "alarm").complete("observed", "observed", 4000, true)
        val skipped = success.begin(5000, "alarm").complete("skipped", "privacy_blocked", 6000, true)
        assertEquals(4000L, skipped.lastSuccessfulAtMillis)
        assertNull(skipped.lastFailureAtMillis)
        assertEquals(0, skipped.consecutiveFailures)
        assertEquals(true, skipped.toolCalled)
        val failure = skipped.begin(7000, "alarm").complete("failed", "vision_timeout", 10000, true, failed = true)
        val nextWake = failure.begin(11000, "alarm")
        assertNull(nextWake.toolCalled)
        assertNull(nextWake.durationMillis)
        assertEquals(10000L, nextWake.lastFailureAtMillis)
        assertEquals(1, nextWake.consecutiveFailures)
    }
}
