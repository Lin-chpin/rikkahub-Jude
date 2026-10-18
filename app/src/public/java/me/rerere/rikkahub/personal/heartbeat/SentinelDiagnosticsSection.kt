package me.rerere.rikkahub.personal.heartbeat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SentinelDiagnosticsSection(diagnostics: SentinelDiagnostics, state: SentinelState, nextWakeAt: Long?, enabled: Boolean) {
    fun timestamp(value: Long?) = value?.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
    val noRecord = stringResource(R.string.heartbeat_no_record)
    HeartbeatInfoRow(stringResource(R.string.sentinel_diagnostics_mode), stringResource(when {
        !enabled -> R.string.heartbeat_status_disabled
        state.pauseReason != null -> R.string.sentinel_paused
        state.active -> R.string.sentinel_diagnostics_short_cycle
        else -> R.string.sentinel_diagnostics_regular_cycle
    }))
    HeartbeatInfoRow(stringResource(R.string.heartbeat_run_status_label), stringResource(when (diagnostics.phase) {
        "waking" -> R.string.sentinel_diagnostics_waking
        "observing" -> R.string.sentinel_diagnostics_observing
        "observed" -> R.string.sentinel_diagnostics_observed
        "not_called" -> R.string.sentinel_diagnostics_not_called
        "skipped" -> R.string.sentinel_diagnostics_skipped
        "unavailable" -> R.string.sentinel_diagnostics_unavailable
        "cancelled" -> R.string.sentinel_diagnostics_cancelled
        "failed", "wake_failed" -> R.string.sentinel_diagnostics_failed
        else -> R.string.heartbeat_status_idle
    }))
    HeartbeatInfoRow(stringResource(R.string.sentinel_diagnostics_tool_called), stringResource(when (diagnostics.toolCalled) {
        true -> R.string.sentinel_diagnostics_called_yes
        false -> R.string.sentinel_diagnostics_called_no
        null -> R.string.sentinel_diagnostics_called_unknown
    }))
    HeartbeatInfoRow(stringResource(R.string.heartbeat_run_reason_label), sentinelReasonText(diagnostics.reason))
    diagnostics.reason.takeUnless { it == "not_started" }?.let { Text(stringResource(R.string.sentinel_diagnostics_reason_code, it)) }
    HeartbeatInfoRow(stringResource(R.string.heartbeat_trigger_source_label), diagnostics.triggerSource ?: noRecord)
    HeartbeatInfoRow(stringResource(R.string.heartbeat_run_updated_at_label), timestamp(diagnostics.updatedAtMillis) ?: noRecord)
    HeartbeatInfoRow(stringResource(R.string.heartbeat_run_duration_label), diagnostics.durationMillis?.let { "${it / 1000}s" } ?: noRecord)
    HeartbeatInfoRow(stringResource(R.string.heartbeat_last_success_label), timestamp(diagnostics.lastSuccessfulAtMillis) ?: noRecord)
    HeartbeatInfoRow(stringResource(R.string.heartbeat_last_failure_label), timestamp(diagnostics.lastFailureAtMillis) ?: noRecord)
    diagnostics.lastFailureReason?.let { HeartbeatInfoRow(stringResource(R.string.heartbeat_last_failure_reason_label), sentinelReasonText(it)) }
    HeartbeatInfoRow(stringResource(R.string.heartbeat_consecutive_failures_label), diagnostics.consecutiveFailures.toString())
    HeartbeatInfoRow(stringResource(R.string.heartbeat_last_run_duration_label), diagnostics.lastCompletedDurationMillis?.let { "${it / 1000}s" } ?: noRecord)
    HeartbeatInfoRow(stringResource(R.string.sentinel_diagnostics_round), state.consecutiveRounds.toString())
    state.pauseReason?.let { HeartbeatInfoRow(stringResource(R.string.sentinel_diagnostics_pause), sentinelReasonText(it)) }
    if (enabled) HeartbeatInfoRow(stringResource(R.string.sentinel_diagnostics_next), if (state.active) timestamp(nextWakeAt) ?: noRecord else stringResource(R.string.sentinel_waiting))
    diagnostics.lastVisionResponse?.let { HeartbeatResponseStats(it, R.string.sentinel_vision_response_usage) }
}

@Composable
private fun sentinelReasonText(reason: String): String {
    HeartbeatRunReason.entries.firstOrNull { it.name.equals(reason, ignoreCase = true) }?.let { return it.displayText() }
    return stringResource(when (reason) {
        "observed" -> R.string.sentinel_diagnostics_observed_reason
        "observing", "awaiting_ai" -> R.string.sentinel_diagnostics_waiting_reason
        "ai_skipped" -> R.string.sentinel_diagnostics_skipped_reason
        "privacy_blocked", "secure_content" -> R.string.sentinel_diagnostics_private_reason
        "screen_app_unknown" -> R.string.sentinel_diagnostics_unknown_app_reason
        "screen_changed" -> R.string.sentinel_diagnostics_changed_reason
        "cycle_limit" -> R.string.sentinel_diagnostics_limit_reason
        "ordinary_wait" -> R.string.sentinel_diagnostics_regular_cycle
        "device_asleep", "device_locked" -> R.string.sentinel_diagnostics_sleep_reason
        "obsolete" -> R.string.sentinel_diagnostics_cancelled_reason
        "not_started" -> R.string.heartbeat_no_record
        "heartbeat_prompt_required" -> R.string.sentinel_prompt_required
        "vision_configuration_required" -> R.string.sentinel_vision_required
        else -> when {
            reason.startsWith("accessibility") -> R.string.sentinel_permission_problem
            reason.startsWith("vision") -> R.string.sentinel_vision_problem
            reason.startsWith("screenshot") || reason.startsWith("capture") -> R.string.sentinel_capture_problem
            else -> R.string.sentinel_diagnostics_error_reason
        }
    })
}
