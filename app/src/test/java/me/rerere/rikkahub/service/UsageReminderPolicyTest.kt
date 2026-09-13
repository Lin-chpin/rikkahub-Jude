package me.rerere.rikkahub.service

import me.rerere.usagetracker.UsageReminderAppState
import me.rerere.usagetracker.UsageReminderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageReminderPolicyTest {
    @Test
    fun notifiesOncePerForegroundEventAndAutoIgnoresAfterThirdReminder() {
        var state = UsageReminderAppState(lastEventTimeMillis = 100L)
        val thresholdMillis = 60L * 60_000L

        assertFalse(
            UsageReminderPolicy.evaluate(state, thresholdMillis - 1L, thresholdMinutes = 60).shouldNotify
        )

        val first = UsageReminderPolicy.evaluate(state, thresholdMillis, thresholdMinutes = 60)
        assertTrue(first.shouldNotify)
        state = first.state

        assertFalse(
            UsageReminderPolicy.evaluate(state, thresholdMillis + 60_000L, thresholdMinutes = 60).shouldNotify
        )

        repeat(2) { index ->
            val next = UsageReminderPolicy.evaluate(
                state.copy(lastEventTimeMillis = 200L + index),
                thresholdMillis + 120_000L + index,
                thresholdMinutes = 60,
            )
            assertTrue(next.shouldNotify)
            state = next.state
        }

        assertEquals(3, state.reminderCount)
        assertTrue(state.ignored)
        assertFalse(
            UsageReminderPolicy.evaluate(
                state.copy(lastEventTimeMillis = 999L),
                thresholdMillis + 300_000L,
                thresholdMinutes = 60,
            ).shouldNotify
        )
    }

    @Test
    fun resetStartsANewReminderCycle() {
        val ignored = UsageReminderAppState(
            reminderCount = 3,
            ignored = true,
            lastEventTimeMillis = 100L,
            lastReminderUsageMillis = 200L,
            lastReminderEventTimeMillis = 100L,
        )

        val reset = UsageReminderPolicy.reset(ignored)

        assertEquals(0, reset.reminderCount)
        assertFalse(reset.ignored)
        assertEquals(0L, reset.lastEventTimeMillis)
        assertEquals(0L, reset.lastReminderUsageMillis)
        assertEquals(0L, reset.lastReminderEventTimeMillis)
    }
}
