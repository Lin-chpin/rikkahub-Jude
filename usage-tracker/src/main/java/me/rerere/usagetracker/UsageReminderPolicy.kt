package me.rerere.usagetracker

data class UsageReminderEvaluation(
    val state: UsageReminderAppState,
    val shouldNotify: Boolean,
)

object UsageReminderPolicy {
    const val AUTO_IGNORE_AFTER_REMINDERS = 3

    fun evaluate(
        current: UsageReminderAppState,
        usageMillis: Long,
        thresholdMinutes: Int,
    ): UsageReminderEvaluation {
        val thresholdMillis = thresholdMinutes.coerceAtLeast(0).toLong() * 60_000L
        val shouldNotify = !current.ignored &&
            usageMillis >= thresholdMillis &&
            current.lastEventTimeMillis > current.lastReminderEventTimeMillis

        if (!shouldNotify) {
            return UsageReminderEvaluation(current, shouldNotify = false)
        }

        val reminderCount = current.reminderCount + 1
        return UsageReminderEvaluation(
            state = current.copy(
                reminderCount = reminderCount,
                ignored = reminderCount >= AUTO_IGNORE_AFTER_REMINDERS,
                lastReminderUsageMillis = usageMillis,
                lastReminderEventTimeMillis = current.lastEventTimeMillis,
            ),
            shouldNotify = true,
        )
    }

    fun reset(current: UsageReminderAppState): UsageReminderAppState {
        return current.copy(
            reminderCount = 0,
            ignored = false,
            lastEventTimeMillis = 0L,
            lastReminderUsageMillis = 0L,
            lastReminderEventTimeMillis = 0L,
        )
    }
}
