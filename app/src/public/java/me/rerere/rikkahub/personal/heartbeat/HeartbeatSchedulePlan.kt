package me.rerere.rikkahub.personal.heartbeat

import kotlinx.serialization.Serializable

/** A user-visible set of future wake times created by the assistant. */
@Serializable
data class HeartbeatSchedulePlan(
    val assistantId: String? = null,
    val wakeAtMillis: List<Long> = emptyList(),
    val repeatIntervalMinutes: Long? = null,
    val recurrence: String? = null,
    val recurrenceTimeMinutes: Int? = null,
    val createdAtMillis: Long = 0L,
)
