package me.rerere.rikkahub.personal.heartbeat

import kotlinx.serialization.Serializable

@Serializable
data class HeartbeatConfig(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 60,
    val maxIntervalMinutes: Int = 180,
    val assistantId: String? = null,
    val generationTimeoutSeconds: Int = 180,
    val maxToolSteps: Int = 5,
    val allowLowRiskWrites: Boolean = false,
    val heartbeatPrompt: String = DEFAULT_HEARTBEAT_PROMPT,
    val readOnlyToolNames: Set<String> = DEFAULT_READ_ONLY_TOOLS,
    val lowRiskWriteToolNames: Set<String> = DEFAULT_LOW_RISK_WRITE_TOOLS,
    val autonomousToolNames: Set<String> = DEFAULT_AUTONOMOUS_TOOLS,
    val sentinel: SentinelConfig = SentinelConfig(),
) {
    fun normalized(): HeartbeatConfig {
        val normalizedMin = minIntervalMinutes.coerceIn(5, 24 * 60)
        val normalizedMax = maxIntervalMinutes.coerceIn(normalizedMin, 7 * 24 * 60)
        val normalizedSentinel = sentinel.normalized()
        return copy(
            sentinel = normalizedSentinel.copy(
                enabled = enabled && sentinel.enabled && normalizedSentinel.fitsHeartbeatIntervals(normalizedMin, normalizedMax),
            ),
            minIntervalMinutes = normalizedMin,
            maxIntervalMinutes = normalizedMax,
            generationTimeoutSeconds = generationTimeoutSeconds.coerceIn(30, 10 * 60),
            maxToolSteps = maxToolSteps.coerceIn(1, 8),
            heartbeatPrompt = heartbeatPrompt.take(MAX_HEARTBEAT_PROMPT_LENGTH),
            readOnlyToolNames = readOnlyToolNames.map(String::trim).filter(String::isNotEmpty).toSet(),
            lowRiskWriteToolNames = lowRiskWriteToolNames.map(String::trim).filter(String::isNotEmpty).toSet(),
            autonomousToolNames = autonomousToolNames.map(String::trim).filter(String::isNotEmpty).toSet(),
        )
    }

    companion object {
        const val DEFAULT_HEARTBEAT_PROMPT = ""
        const val LEGACY_DEFAULT_HEARTBEAT_PROMPT =
            "Decide whether a brief, natural proactive message would be useful now. " +
                "If not, reply with exactly [PASS]. Do not mention scheduling, heartbeat, " +
                "background execution, internal tools, or data sources."
        private const val MAX_HEARTBEAT_PROMPT_LENGTH = 4_000
        val DEFAULT_READ_ONLY_TOOLS = setOf(
            "eval_javascript",
            "get_time_info",
            "get_device_usage_stats",
        )
        val DEFAULT_LOW_RISK_WRITE_TOOLS = setOf(
            "clipboard_tool",
            "text_to_speech",
            "post_moment",
            "post_anonymous_question",
        )
        val DEFAULT_AUTONOMOUS_TOOLS = setOf(
            "ask_user",
            "delete_anonymous_question",
            "delete_moment",
            "memory_tool",
            "request_voice_call",
            "usage_lock_control",
        )
    }
}

private fun SentinelConfig.fitsHeartbeatIntervals(minimum: Int, maximum: Int) =
    minIntervalMinutes <= minimum && maxIntervalMinutes <= maximum

fun HeartbeatConfig.sentinelIntervalsExceedHeartbeat(): Boolean {
    val heartbeatMin = minIntervalMinutes.coerceIn(5, 24 * 60)
    val heartbeatMax = maxIntervalMinutes.coerceIn(heartbeatMin, 7 * 24 * 60)
    return !sentinel.normalized().fitsHeartbeatIntervals(heartbeatMin, heartbeatMax)
}
