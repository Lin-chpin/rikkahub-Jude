package me.rerere.rikkahub.personal.heartbeat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HeartbeatCurrentDiagnosticsSection(
    runStatus: HeartbeatRunStatus,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.heartbeat_diagnostics_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.heartbeat_run_status, runStatus.phase.displayText()))
        if (runStatus.reason != HeartbeatRunReason.NONE) {
            Text(stringResource(R.string.heartbeat_run_reason, runStatus.reason.displayText()))
        }
        runStatus.triggerSource?.takeIf(String::isNotBlank)?.let { source ->
            Text(stringResource(R.string.heartbeat_trigger_source, source))
        }
        runStatus.updatedAtMillis?.let { updatedAt ->
            Text(stringResource(R.string.heartbeat_run_updated_at, formatTimestamp(updatedAt)))
        }
        runStatus.durationMillis?.let { duration ->
            Text(stringResource(R.string.heartbeat_run_duration, formatDuration(duration)))
        }
        runStatus.detail?.takeIf(String::isNotBlank)?.let { detail ->
            Text(stringResource(R.string.heartbeat_run_detail, detail))
        }
    }
}

@Composable
internal fun HeartbeatHistoryDiagnosticsSection(
    diagnostics: HeartbeatRunDiagnostics,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(
                R.string.heartbeat_last_success,
                diagnostics.lastSuccessfulRunAtMillis?.let(::formatTimestamp)
                    ?: stringResource(R.string.heartbeat_no_record),
            ),
        )
        Text(
            stringResource(
                R.string.heartbeat_last_failure,
                diagnostics.lastFailureAtMillis?.let(::formatTimestamp)
                    ?: stringResource(R.string.heartbeat_no_record),
            ),
        )
        diagnostics.lastFailureReason?.let { reason ->
            Text(stringResource(R.string.heartbeat_last_failure_reason, reason.displayText()))
        }
        diagnostics.lastFailureDetail?.takeIf(String::isNotBlank)?.let { detail ->
            Text(stringResource(R.string.heartbeat_last_failure_detail, detail))
        }
        Text(
            stringResource(
                R.string.heartbeat_consecutive_failures,
                diagnostics.consecutiveFailures,
            ),
        )
        if (diagnostics.consecutiveFailures >= 3) {
            Text(
                text = stringResource(R.string.heartbeat_repeated_failure_hint),
                color = MaterialTheme.colorScheme.error,
            )
        }
        diagnostics.nextRetryAtMillis?.let { retryAt ->
            Text(stringResource(R.string.heartbeat_next_retry, formatTimestamp(retryAt)))
        }
        diagnostics.lastRunDurationMillis?.let { duration ->
            Text(stringResource(R.string.heartbeat_last_run_duration, formatDuration(duration)))
        }
        diagnostics.lastStateRecoveryAtMillis?.let { recoveredAt ->
            Text(
                stringResource(
                    R.string.heartbeat_state_recovered,
                    diagnostics.lastStateRecoveryArea.orEmpty(),
                    formatTimestamp(recoveredAt),
                ),
            )
        }
    }
}

@Composable
private fun HeartbeatRunPhase.displayText(): String = stringResource(
    when (this) {
        HeartbeatRunPhase.IDLE -> R.string.heartbeat_status_idle
        HeartbeatRunPhase.QUEUED -> R.string.heartbeat_status_queued
        HeartbeatRunPhase.RUNNING -> R.string.heartbeat_status_running
        HeartbeatRunPhase.SENT -> R.string.heartbeat_status_sent
        HeartbeatRunPhase.PASS -> R.string.heartbeat_status_pass
        HeartbeatRunPhase.SKIPPED_PENDING_USER -> R.string.heartbeat_status_pending_user
        HeartbeatRunPhase.SKIPPED_BUSY -> R.string.heartbeat_status_busy
        HeartbeatRunPhase.SKIPPED_NO_MODEL -> R.string.heartbeat_status_no_model
        HeartbeatRunPhase.SKIPPED_DISABLED -> R.string.heartbeat_status_disabled
        HeartbeatRunPhase.TIMED_OUT -> R.string.heartbeat_status_timed_out
        HeartbeatRunPhase.CANCELLED -> R.string.heartbeat_status_cancelled
        HeartbeatRunPhase.FAILED -> R.string.heartbeat_status_failed
        HeartbeatRunPhase.TESTED -> R.string.heartbeat_status_tested
    },
)

@Composable
private fun HeartbeatRunReason.displayText(): String = stringResource(
    when (this) {
        HeartbeatRunReason.NONE -> R.string.heartbeat_reason_none
        HeartbeatRunReason.MESSAGE_SENT -> R.string.heartbeat_reason_message_sent
        HeartbeatRunReason.MODEL_DECIDED_PASS -> R.string.heartbeat_reason_model_pass
        HeartbeatRunReason.NOVELTY_FILTERED -> R.string.heartbeat_reason_novelty_filtered
        HeartbeatRunReason.READ_ONLY_TEST -> R.string.heartbeat_reason_read_only_test
        HeartbeatRunReason.READ_ONLY_WOULD_SEND -> R.string.heartbeat_reason_read_only_would_send
        HeartbeatRunReason.USER_REPLY_PENDING -> R.string.heartbeat_reason_user_reply_pending
        HeartbeatRunReason.USER_RETURNED -> R.string.heartbeat_reason_user_returned
        HeartbeatRunReason.VOICE_CALL_ACTIVE -> R.string.heartbeat_reason_voice_call_active
        HeartbeatRunReason.CONVERSATION_BUSY -> R.string.heartbeat_reason_conversation_busy
        HeartbeatRunReason.HEARTBEAT_ALREADY_RUNNING -> R.string.heartbeat_reason_already_running
        HeartbeatRunReason.MINIMUM_INTERVAL -> R.string.heartbeat_reason_minimum_interval
        HeartbeatRunReason.NO_MODEL -> R.string.heartbeat_reason_no_model
        HeartbeatRunReason.DISABLED -> R.string.heartbeat_reason_disabled
        HeartbeatRunReason.TIMEOUT -> R.string.heartbeat_reason_timeout
        HeartbeatRunReason.NETWORK_TIMEOUT -> R.string.heartbeat_reason_network_timeout
        HeartbeatRunReason.NETWORK_UNAVAILABLE -> R.string.heartbeat_reason_network_unavailable
        HeartbeatRunReason.AUTHENTICATION_FAILED -> R.string.heartbeat_reason_authentication_failed
        HeartbeatRunReason.RATE_LIMITED -> R.string.heartbeat_reason_rate_limited
        HeartbeatRunReason.STORAGE_FAILURE -> R.string.heartbeat_reason_storage_failure
        HeartbeatRunReason.TOOL_EXECUTION_FAILURE -> R.string.heartbeat_reason_tool_failure
        HeartbeatRunReason.SERVICE_START_FAILURE -> R.string.heartbeat_reason_service_start_failure
        HeartbeatRunReason.GENERATION_FAILURE -> R.string.heartbeat_reason_generation_failure
        HeartbeatRunReason.CANCELLED -> R.string.heartbeat_reason_cancelled
        HeartbeatRunReason.TARGET_CHANGED -> R.string.heartbeat_reason_target_changed
        HeartbeatRunReason.STATE_RECOVERED -> R.string.heartbeat_reason_state_recovered
    },
)

private fun formatTimestamp(timestampMillis: Long): String =
    DateFormat.getDateTimeInstance().format(Date(timestampMillis))

private fun formatDuration(durationMillis: Long): String = when {
    durationMillis < 1_000L -> "${durationMillis}ms"
    durationMillis < 60_000L -> "${durationMillis / 1_000L}s"
    else -> "${durationMillis / 60_000L}m ${durationMillis % 60_000L / 1_000L}s"
}
