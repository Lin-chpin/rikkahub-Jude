package me.rerere.rikkahub.personal.heartbeat

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.roundToInt

/** Time- and event-driven pressure used to choose the next heartbeat window. */
@Serializable
data class HeartbeatDesireState(
    val attachment: Double = 0.22,
    val curiosity: Double = 0.28,
    val fatigue: Double = 0.10,
    val updatedAtMillis: Long = 0L,
) {
    fun advance(nowMillis: Long): HeartbeatDesireState {
        val reference = updatedAtMillis.takeIf { it > 0L } ?: nowMillis
        val hours = ((nowMillis - reference).coerceAtLeast(0L) / 3_600_000.0)
            .coerceAtMost(24.0)
        return copy(
            attachment = clamp(attachment + 0.018 * hours),
            curiosity = clamp(curiosity + 0.015 * hours),
            fatigue = clamp(fatigue + 0.012 * hours),
            updatedAtMillis = nowMillis,
        )
    }

    fun afterUserMessage(nowMillis: Long): HeartbeatDesireState {
        val current = advance(nowMillis)
        return current.copy(
            attachment = clamp(current.attachment * 0.58),
            curiosity = clamp(current.curiosity * 0.70),
            fatigue = clamp(current.fatigue * 0.82),
        )
    }

    fun afterDelivery(nowMillis: Long): HeartbeatDesireState {
        val current = advance(nowMillis)
        return current.copy(
            attachment = clamp(current.attachment * 0.58),
            curiosity = clamp(current.curiosity * 0.55),
            fatigue = clamp(current.fatigue + 0.08),
        )
    }

    fun pressure(): Double = clamp(
        attachment * 0.40 +
            curiosity * 0.35 +
            (1.0 - fatigue) * 0.25,
    )

    fun nextWakeMinutes(minimum: Int, maximum: Int): Int {
        if (minimum >= maximum) return minimum
        val curve = exp(-2.2 * pressure())
        return (minimum + (maximum - minimum) * curve)
            .roundToInt()
            .coerceIn(minimum, maximum)
    }

    private fun clamp(value: Double): Double = value.coerceIn(0.0, 1.0)
}
