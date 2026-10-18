package me.rerere.rikkahub.personal.heartbeat

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessage

@Serializable
data class SentinelDiagnostics(
    val phase: String = "idle",
    val reason: String = "not_started",
    val wakeStartedAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val triggerSource: String? = null,
    val toolCalled: Boolean? = null,
    val durationMillis: Long? = null,
    val lastCompletedDurationMillis: Long? = null,
    val lastSuccessfulAtMillis: Long? = null,
    val lastFailureAtMillis: Long? = null,
    val lastFailureReason: String? = null,
    val consecutiveFailures: Int = 0,
    val lastVisionResponse: UIMessage? = null,
) {
    fun begin(startedAt: Long, source: String) = copy(
        phase = "waking", reason = "awaiting_ai", wakeStartedAtMillis = startedAt,
        updatedAtMillis = startedAt, triggerSource = source.take(40), toolCalled = null, durationMillis = null,
    )

    fun complete(phase: String, reason: String, now: Long, called: Boolean, failed: Boolean = false) = copy(
        phase = phase, reason = reason, updatedAtMillis = now, toolCalled = called,
        durationMillis = (now - wakeStartedAtMillis).coerceAtLeast(0),
        lastCompletedDurationMillis = (now - wakeStartedAtMillis).coerceAtLeast(0),
        lastSuccessfulAtMillis = if (phase == "observed") now else lastSuccessfulAtMillis,
        lastFailureAtMillis = if (failed) now else lastFailureAtMillis,
        lastFailureReason = if (failed) reason else lastFailureReason,
        consecutiveFailures = if (failed) (consecutiveFailures + 1).coerceAtMost(100) else if (phase == "observed") 0 else consecutiveFailures,
    )
}
