package me.rerere.rikkahub.personal.heartbeat

import kotlin.math.roundToInt
import kotlin.random.Random

object HeartbeatScheduleTiming {
    private const val JITTER_FRACTION = 0.08

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
