package me.rerere.rikkahub.personal.heartbeat

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.random.Random

@Serializable
data class HeartbeatRunDiagnostics(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val consecutiveFailures: Int = 0,
    val lastSuccessfulRunAtMillis: Long? = null,
    val lastFailureAtMillis: Long? = null,
    val lastFailureReason: HeartbeatRunReason? = null,
    val lastFailureDetail: String? = null,
    val nextRetryAtMillis: Long? = null,
    val lastRunDurationMillis: Long? = null,
    val lastTriggerSource: String? = null,
    val lastStateRecoveryAtMillis: Long? = null,
    val lastStateRecoveryArea: String? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class HeartbeatRunImpact {
    NEUTRAL,
    SUCCESS,
    FAILURE,
    SKIPPED,
}

object HeartbeatRetryPolicy {
    private const val BASE_DELAY_MILLIS = 5 * 60_000L
    private const val MAX_DELAY_MILLIS = 6 * 60 * 60_000L
    private const val JITTER_FRACTION = 0.10

    fun delayMillis(
        consecutiveFailures: Int,
        jitterUnit: Double = Random.nextDouble(),
    ): Long {
        val failureIndex = (consecutiveFailures - 1).coerceIn(0, 10)
        val exponentialDelay = BASE_DELAY_MILLIS * 2.0.pow(failureIndex)
        val cappedDelay = exponentialDelay.coerceAtMost(MAX_DELAY_MILLIS.toDouble())
        val normalizedJitter = jitterUnit.coerceIn(0.0, 1.0)
        val jitterFactor = 1.0 - JITTER_FRACTION + 2.0 * JITTER_FRACTION * normalizedJitter
        return (cappedDelay * jitterFactor)
            .toLong()
            .coerceAtMost(MAX_DELAY_MILLIS)
    }

    fun nextRetryAtMillis(
        completedAtMillis: Long,
        consecutiveFailures: Int,
        jitterUnit: Double = Random.nextDouble(),
    ): Long = completedAtMillis + delayMillis(consecutiveFailures, jitterUnit)
}

fun HeartbeatRunDiagnostics.afterCompletion(
    impact: HeartbeatRunImpact,
    reason: HeartbeatRunReason,
    detail: String?,
    completedAtMillis: Long,
    durationMillis: Long?,
    triggerSource: String?,
    retryJitterUnit: Double = Random.nextDouble(),
): HeartbeatRunDiagnostics {
    val observed = copy(
        schemaVersion = HeartbeatRunDiagnostics.CURRENT_SCHEMA_VERSION,
        lastRunDurationMillis = durationMillis,
        lastTriggerSource = triggerSource,
    )
    return when (impact) {
        HeartbeatRunImpact.NEUTRAL -> observed
        HeartbeatRunImpact.SUCCESS -> observed.copy(
            consecutiveFailures = 0,
            lastSuccessfulRunAtMillis = completedAtMillis,
            nextRetryAtMillis = null,
        )
        HeartbeatRunImpact.FAILURE -> {
            val failures = (consecutiveFailures + 1).coerceAtMost(100)
            observed.copy(
                consecutiveFailures = failures,
                lastFailureAtMillis = completedAtMillis,
                lastFailureReason = reason,
                lastFailureDetail = detail,
                nextRetryAtMillis = HeartbeatRetryPolicy.nextRetryAtMillis(
                    completedAtMillis = completedAtMillis,
                    consecutiveFailures = failures,
                    jitterUnit = retryJitterUnit,
                ),
            )
        }
        HeartbeatRunImpact.SKIPPED -> observed.copy(nextRetryAtMillis = null)
    }
}
