package me.rerere.rikkahub.personal.heartbeat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.R

@Composable
internal fun HeartbeatSetupChecklist(
    model: Model?,
    notificationReady: Boolean,
    exactAlarmReady: Boolean,
    batteryOptimizationIgnored: Boolean?,
    onRequestNotifications: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    if (model != null &&
        notificationReady &&
        exactAlarmReady &&
        batteryOptimizationIgnored == true
    ) {
        return
    }

    HeartbeatGlassSection {
        Text(
            text = stringResource(R.string.heartbeat_setup_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.heartbeat_setup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HeartbeatSetupRow(
            title = stringResource(R.string.heartbeat_setup_model_title),
            detail = model?.let {
                stringResource(
                    R.string.heartbeat_setup_model_ready,
                    it.displayName.ifBlank { it.modelId },
                )
            } ?: stringResource(R.string.heartbeat_setup_model_missing),
            status = if (model != null) {
                HeartbeatSetupStatus.READY
            } else {
                HeartbeatSetupStatus.ACTION_REQUIRED
            },
        )
        HeartbeatSetupRow(
            title = stringResource(R.string.heartbeat_setup_notification_title),
            detail = if (notificationReady) {
                stringResource(R.string.heartbeat_setup_permission_ready)
            } else {
                stringResource(R.string.heartbeat_setup_notification_missing)
            },
            status = if (notificationReady) {
                HeartbeatSetupStatus.READY
            } else {
                HeartbeatSetupStatus.ACTION_REQUIRED
            },
            actionLabel = if (notificationReady) null else stringResource(R.string.heartbeat_setup_open_settings),
            onAction = if (notificationReady) null else onRequestNotifications,
        )
        HeartbeatSetupRow(
            title = stringResource(R.string.heartbeat_setup_exact_alarm_title),
            detail = if (exactAlarmReady) {
                stringResource(R.string.heartbeat_setup_permission_ready)
            } else {
                stringResource(R.string.heartbeat_setup_exact_alarm_missing)
            },
            status = if (exactAlarmReady) {
                HeartbeatSetupStatus.READY
            } else {
                HeartbeatSetupStatus.ACTION_REQUIRED
            },
            actionLabel = if (exactAlarmReady) null else stringResource(R.string.heartbeat_setup_open_settings),
            onAction = if (exactAlarmReady) null else onRequestExactAlarm,
        )
        HeartbeatSetupRow(
            title = stringResource(R.string.heartbeat_setup_battery_title),
            detail = when (batteryOptimizationIgnored) {
                true -> stringResource(R.string.heartbeat_setup_battery_ready)
                false -> stringResource(R.string.heartbeat_setup_battery_missing)
                null -> stringResource(R.string.heartbeat_setup_battery_unknown)
            },
            status = when (batteryOptimizationIgnored) {
                true -> HeartbeatSetupStatus.READY
                false -> HeartbeatSetupStatus.ACTION_REQUIRED
                null -> HeartbeatSetupStatus.CHECK_MANUALLY
            },
            actionLabel = if (batteryOptimizationIgnored == true) {
                null
            } else {
                stringResource(R.string.heartbeat_setup_open_settings)
            },
            onAction = if (batteryOptimizationIgnored == true) null else onOpenBatterySettings,
        )
    }
}

private enum class HeartbeatSetupStatus {
    READY,
    ACTION_REQUIRED,
    CHECK_MANUALLY,
}

@Composable
private fun HeartbeatSetupRow(
    title: String,
    detail: String,
    status: HeartbeatSetupStatus,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(
                text = stringResource(
                    when (status) {
                        HeartbeatSetupStatus.READY -> R.string.heartbeat_setup_status_ready
                        HeartbeatSetupStatus.ACTION_REQUIRED -> R.string.heartbeat_setup_status_action
                        HeartbeatSetupStatus.CHECK_MANUALLY -> R.string.heartbeat_setup_status_check
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = when (status) {
                    HeartbeatSetupStatus.READY -> MaterialTheme.colorScheme.primary
                    HeartbeatSetupStatus.ACTION_REQUIRED -> MaterialTheme.colorScheme.error
                    HeartbeatSetupStatus.CHECK_MANUALLY -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
