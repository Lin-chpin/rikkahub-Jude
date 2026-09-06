package me.rerere.rikkahub.personal.heartbeat

import kotlin.math.roundToInt
import kotlin.random.Random

object HeartbeatScheduleTiming {
    private const val JITTER_FRACTION = 0.08
    private const val MILLIS_PER_MINUTE = 60_000L

    /** Calculates the next regular trigger from the selected interval anchor. */
    fun nextRegularTriggerAtMillis(
        nowMillis: Long,
        delayMinutes: Long,
        anchorAtMillis: Long?,
        preserveAnchor: Boolean,
        minimumLeadMillis: Long = 1_000L,
    ): Long {
        val delayMillis = delayMinutes * MILLIS_PER_MINUTE
        val anchorAt = anchorAtMillis ?: nowMillis
        val earliestAt = nowMillis + if (preserveAnchor) minimumLeadMillis else delayMillis
        return maxOf(earliestAt, anchorAt + delayMillis)
    }

    fun jitteredDelayMinutes(
        baseMinutes: Int,
        minimumMinutes: Int,
        maximumMinutes: Int,
        jitterUnit: Double = Random.nextDouble(),
    ): Long {
        if (minimumMinutes >= maximumMinutes) return minimumMinutes.toLong()
        val normalizedJitter = jitterUnit.coerceIn(0.0, 1.0)
        val offset = (normalizedJitter * 2.0 - 1.0) * baseMinutes * JITTER_FRACTION
        return (baseMinutes + offset)
            .roundToInt()
            .coerceIn(minimumMinutes, maximumMinutes)
            .toLong()
    }
}
