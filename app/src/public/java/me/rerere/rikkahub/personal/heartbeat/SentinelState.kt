package me.rerere.rikkahub.personal.heartbeat

import kotlinx.serialization.Serializable

@Serializable
data class SentinelConfig(
    val enabled: Boolean = false,
    val guideAccepted: Boolean = false,
    val minIntervalMinutes: Int = 5,
    val maxIntervalMinutes: Int = 15,
    val visionTimeoutSeconds: Int = 60,
    val generationTimeoutSeconds: Int = 180,
    val verifiedVisionKey: String = "",
    val blockedPackages: Set<String> = emptySet(),
) {
    fun normalized() = copy(
        minIntervalMinutes = minIntervalMinutes.coerceIn(5, 24 * 60),
        maxIntervalMinutes = maxIntervalMinutes.coerceIn(minIntervalMinutes.coerceIn(5, 24 * 60), 7 * 24 * 60),
        generationTimeoutSeconds = generationTimeoutSeconds.coerceIn(30, 600),
        visionTimeoutSeconds = visionTimeoutSeconds.coerceIn(5, generationTimeoutSeconds.coerceIn(30, 600)),
        blockedPackages = blockedPackages.map(String::trim).filter(String::isNotEmpty).toSet(),
    )
}

/** Persistent cycle state, distinct from permission to use the tool. */
@Serializable
data class SentinelState(
    val active: Boolean = false,
    val consecutiveRounds: Int = 0,
    val revision: Long = 0,
    val anchorAtMillis: Long = 0,
    val ordinaryNotBeforeMillis: Long = 0,
    val pauseReason: String? = null,
) {
    fun enter(now: Long) = copy(
        active = true,
        consecutiveRounds = consecutiveRounds + 1,
        anchorAtMillis = now,
        pauseReason = null,
    )
    fun exit(now: Long, reason: String? = null) = copy(
        active = false, consecutiveRounds = 0, anchorAtMillis = now,
        ordinaryNotBeforeMillis = 0, pauseReason = reason,
    )
    fun invalidate() = copy(revision = revision + 1)
    fun canObserve(now: Long) = pauseReason == null && now >= ordinaryNotBeforeMillis
}

fun HeartbeatConfig.forCurrentCycle(state: SentinelState): HeartbeatConfig =
    if (enabled && sentinel.enabled && state.active && state.pauseReason == null) copy(
        minIntervalMinutes = sentinel.minIntervalMinutes,
        maxIntervalMinutes = sentinel.maxIntervalMinutes,
        generationTimeoutSeconds = sentinel.generationTimeoutSeconds,
    ) else this
