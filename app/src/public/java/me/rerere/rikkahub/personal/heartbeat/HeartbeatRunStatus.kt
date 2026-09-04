package me.rerere.rikkahub.personal.heartbeat

import kotlinx.serialization.Serializable

data class HeartbeatRunStatus(
    val phase: HeartbeatRunPhase = HeartbeatRunPhase.IDLE,
    val reason: HeartbeatRunReason = HeartbeatRunReason.NONE,
    val assistantId: String? = null,
    val detail: String? = null,
    val triggerSource: String? = null,
    val startedAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
    val durationMillis: Long? = null,
)

enum class HeartbeatRunPhase {
    IDLE,
    QUEUED,
    RUNNING,
    SENT,
    PASS,
    SKIPPED_PENDING_USER,
    SKIPPED_BUSY,
    SKIPPED_NO_MODEL,
    SKIPPED_DISABLED,
    TIMED_OUT,
    CANCELLED,
    FAILED,
    TESTED,
}

enum class HeartbeatGenerationOutcome {
    SENT,
    PASS,
    PENDING_USER,
    BUSY,
    NO_MODEL,
    TESTED,
}

data class HeartbeatGenerationResult(
    val outcome: HeartbeatGenerationOutcome,
    val reason: HeartbeatRunReason,
    val detail: String? = null,
)

enum class HeartbeatExecutionMode {
    LIVE,
    READ_ONLY_TEST,
}

@Serializable
enum class HeartbeatRunReason {
    NONE,
    MESSAGE_SENT,
    MODEL_DECIDED_PASS,
    NOVELTY_FILTERED,
    READ_ONLY_TEST,
    READ_ONLY_WOULD_SEND,
    USER_REPLY_PENDING,
    USER_RETURNED,
    VOICE_CALL_ACTIVE,
    CONVERSATION_BUSY,
    HEARTBEAT_ALREADY_RUNNING,
    MINIMUM_INTERVAL,
    NO_MODEL,
    DISABLED,
    TIMEOUT,
    NETWORK_TIMEOUT,
    NETWORK_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    RATE_LIMITED,
    STORAGE_FAILURE,
    TOOL_EXECUTION_FAILURE,
    SERVICE_START_FAILURE,
    GENERATION_FAILURE,
    CANCELLED,
    TARGET_CHANGED,
    STATE_RECOVERED,
}
