package me.rerere.rikkahub.personal.heartbeat

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.EXTRA_OPEN_MODEL_SETTINGS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

@Composable
internal fun SentinelSettingsSection(
    config: HeartbeatConfig, settings: Settings, assistant: Assistant, state: SentinelState,
    onChange: (HeartbeatConfig) -> Unit, onResumeObservation: () -> Unit,
) {
    val context = LocalContext.current
    val vm: SentinelSetupViewModel = viewModel()
    val verification by vm.verification.collectAsStateWithLifecycle()
    val captureReady by SentinelCaptureService.available.collectAsStateWithLifecycle()
    val selection = remember(settings, assistant.chatModelId) { SentinelVision.configured(settings, assistant) }
    val visionReady = SentinelVision.ready(selection, config.sentinel)
    var showSetup by remember(assistant.id) { mutableStateOf(false) }
    var showIntervalMismatch by remember(assistant.id) { mutableStateOf(false) }
    var showBlockedApps by remember(assistant.id) { mutableStateOf(false) }
    var acknowledged by remember(assistant.id) { mutableStateOf(false) }
    val testResultResource = when (verification.failureReason) {
        HeartbeatRunReason.NETWORK_TIMEOUT -> R.string.sentinel_test_timeout
        HeartbeatRunReason.AUTHENTICATION_FAILED -> R.string.sentinel_test_auth_failed
        HeartbeatRunReason.RATE_LIMITED -> R.string.sentinel_test_rate_limited
        HeartbeatRunReason.NETWORK_UNAVAILABLE -> R.string.sentinel_test_network_failed
        else -> if (verification.failed) R.string.sentinel_test_failed else R.string.sentinel_test_success
    }
    LaunchedEffect(verification.completedAttempt) {
        if (verification.completedAttempt > 0 && !verification.running && verification.testedKey == selection?.key) {
            Toast.makeText(context, testResultResource, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(verification.key, selection?.key) {
        if (verification.key.isNotBlank() && verification.key == selection?.key && config.sentinel.verifiedVisionKey != verification.key) {
            onChange(config.copy(sentinel = config.sentinel.copy(verifiedVisionKey = verification.key)))
        }
    }

    HeartbeatGlassSection {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sentinel_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = config.sentinel.enabled, onCheckedChange = { enable ->
                if (!enable) onChange(config.copy(sentinel = config.sentinel.copy(enabled = false)))
                else if (!config.enabled) Toast.makeText(context, R.string.sentinel_heartbeat_required, Toast.LENGTH_SHORT).show()
                else if (config.sentinelIntervalsExceedHeartbeat()) showIntervalMismatch = true
                else if (!config.sentinel.guideAccepted || !captureReady || !visionReady || config.heartbeatPrompt.isBlank()) showSetup = true
                else onChange(config.copy(sentinel = config.sentinel.copy(enabled = true)))
            })
        }
        TextButton(onClick = { showBlockedApps = true }) {
            Text(stringResource(R.string.sentinel_blocked_apps_button, config.sentinel.blockedPackages.size))
        }
        if (state.pauseReason != null) {
            Text(stringResource(when {
                state.pauseReason == "heartbeat_prompt_required" -> R.string.sentinel_prompt_required
                state.pauseReason.startsWith("vision") -> R.string.sentinel_vision_problem
                state.pauseReason.startsWith("accessibility") -> R.string.sentinel_permission_problem
                else -> R.string.sentinel_capture_problem
            }), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { if (captureReady && visionReady) onResumeObservation() else showSetup = true }) {
                Text(stringResource(R.string.sentinel_recheck))
            }
        }
    }

    if (showBlockedApps) SentinelBlockedAppsDialog(
        blockedPackages = config.sentinel.blockedPackages, vm = vm,
        onSave = { onChange(config.copy(sentinel = config.sentinel.copy(blockedPackages = it))) },
        onDismiss = { showBlockedApps = false },
    )
    HeartbeatExpandableSection(stringResource(R.string.sentinel_runtime_title)) {
        NumberField(stringResource(R.string.heartbeat_min_interval), config.sentinel.minIntervalMinutes,
            { onChange(config.copy(sentinel = config.sentinel.copy(minIntervalMinutes = it))) }, stringResource(R.string.sentinel_min_hint))
        NumberField(stringResource(R.string.heartbeat_max_interval), config.sentinel.maxIntervalMinutes,
            { onChange(config.copy(sentinel = config.sentinel.copy(maxIntervalMinutes = it))) }, stringResource(R.string.sentinel_max_hint))
        NumberField(stringResource(R.string.sentinel_vision_timeout), config.sentinel.visionTimeoutSeconds,
            { onChange(config.copy(sentinel = config.sentinel.copy(visionTimeoutSeconds = it))) }, stringResource(R.string.sentinel_vision_timeout_hint))
        NumberField(stringResource(R.string.heartbeat_timeout), config.sentinel.generationTimeoutSeconds,
            { onChange(config.copy(sentinel = config.sentinel.copy(generationTimeoutSeconds = it))) }, stringResource(R.string.sentinel_run_timeout_hint))
        if (config.sentinelIntervalsExceedHeartbeat()) {
            Text(stringResource(R.string.sentinel_interval_mismatch_message), color = MaterialTheme.colorScheme.error)
        }
        if (config.sentinel != config.sentinel.normalized()) Text(stringResource(R.string.heartbeat_config_adjustment_warning), color = MaterialTheme.colorScheme.error)
    }

    if (showIntervalMismatch) AlertDialog(
        onDismissRequest = { showIntervalMismatch = false },
        title = { Text(stringResource(R.string.sentinel_interval_mismatch_title)) },
        text = { Text(stringResource(R.string.sentinel_interval_mismatch_message)) },
        confirmButton = { TextButton(onClick = { showIntervalMismatch = false }) { Text(stringResource(R.string.sentinel_interval_mismatch_ack)) } },
    )

    if (showSetup) AlertDialog(
        onDismissRequest = { showSetup = false }, title = { Text(stringResource(R.string.sentinel_setup_title)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.sentinel_setup_note))
                Text(stringResource(R.string.sentinel_prompt_example), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (config.sentinelIntervalsExceedHeartbeat()) {
                    Text(stringResource(R.string.sentinel_interval_mismatch_message), color = MaterialTheme.colorScheme.error)
                }
                if (config.heartbeatPrompt.isBlank()) Text(stringResource(R.string.sentinel_prompt_required), color = MaterialTheme.colorScheme.error)
                Row {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(stringResource(R.string.sentinel_prompt_ack))
                }
                Text(stringResource(if (visionReady) R.string.sentinel_vision_ready else R.string.sentinel_vision_required))
                TextButton(onClick = {
                    context.startActivity(Intent(context, RouteActivity::class.java).putExtra(EXTRA_OPEN_MODEL_SETTINGS, true))
                }) { Text(stringResource(R.string.sentinel_configure_vision)) }
                TextButton(onClick = { selection?.let { vm.verify(context, it, config.sentinel.visionTimeoutSeconds) } }, enabled = selection != null && !verification.running) {
                    Text(stringResource(if (verification.running) R.string.sentinel_testing else R.string.sentinel_test_vision))
                }
                if (!verification.running && verification.completedAttempt > 0 && verification.testedKey == selection?.key) {
                    Text(stringResource(testResultResource), color = if (verification.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    verification.response?.let { HeartbeatResponseStats(it, R.string.sentinel_vision_test_usage) }
                }
                Text(stringResource(if (captureReady) R.string.sentinel_permission_ready else R.string.sentinel_permission_required))
                Text(stringResource(R.string.sentinel_accessibility_guide), style = MaterialTheme.typography.bodySmall)
                Image(
                    painter = painterResource(R.drawable.sentinel_accessibility_example),
                    contentDescription = stringResource(R.string.sentinel_accessibility_example),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text(stringResource(R.string.sentinel_grant_permission))
                }
            }
        },
        confirmButton = { TextButton(
            enabled = config.enabled && captureReady && visionReady && !config.sentinelIntervalsExceedHeartbeat() && config.heartbeatPrompt.isNotBlank() && (acknowledged || config.sentinel.guideAccepted),
            onClick = {
                onChange(config.copy(sentinel = config.sentinel.copy(enabled = true, guideAccepted = true)))
                onResumeObservation(); showSetup = false
            },
        ) { Text(stringResource(R.string.sentinel_enable)) } },
        dismissButton = { TextButton(onClick = { showSetup = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
