package me.rerere.rikkahub.personal.heartbeat

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import org.koin.android.ext.android.inject
import java.text.DateFormat
import java.util.Date

class HeartbeatSettingsActivity : ComponentActivity() {
    private val settingsStore: SettingsStore by inject()
    private var activeAssistantStore: HeartbeatConfigStore? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RikkahubTheme {
                val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
                val currentAssistant = settings.getCurrentAssistant()
                val currentAssistantId = currentAssistant.id.toString()
                val assistantStore = remember(currentAssistantId) {
                    activeAssistantStore?.close()
                    HeartbeatConfigStore(
                        this@HeartbeatSettingsActivity,
                        assistantId = currentAssistantId,
                        observeChanges = true,
                    ).also { activeAssistantStore = it }
                }
                val storedConfig by assistantStore.config.collectAsStateWithLifecycle()
                val runStatus by assistantStore.runStatus.collectAsStateWithLifecycle()
                val diagnostics by assistantStore.diagnostics.collectAsStateWithLifecycle()
                val nextTriggerAt by assistantStore.nextTriggerAt.collectAsStateWithLifecycle()
                var draft by remember(currentAssistantId, storedConfig) {
                    mutableStateOf(storedConfig)
                }
                val visibleRunStatus = runStatus.takeIf {
                    it.assistantId == currentAssistantId
                } ?: HeartbeatRunStatus()
                val nextTrigger = nextTriggerAt?.let { triggerAt ->
                    DateFormat.getDateTimeInstance().format(Date(triggerAt))
                } ?: stringResource(R.string.heartbeat_not_scheduled)

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                            ),
                            title = {
                                Text(
                                    text = stringResource(R.string.heartbeat_settings_title),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                            },
                            actions = {
                                Switch(
                                    checked = draft.enabled,
                                    onCheckedChange = { draft = draft.copy(enabled = it) },
                                )
                            },
                        )
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                            .imePadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HeartbeatGlassSection {
                            HeartbeatInfoRow(
                                label = stringResource(R.string.heartbeat_current_assistant_label),
                                value = currentAssistant.name,
                            )
                            HeartbeatInfoRow(
                                label = stringResource(R.string.heartbeat_next_trigger_label),
                                value = nextTrigger,
                            )
                            if (visibleRunStatus.phase == HeartbeatRunPhase.QUEUED ||
                                visibleRunStatus.phase == HeartbeatRunPhase.RUNNING
                            ) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        HeartbeatGlassSection {
                            HeartbeatCurrentDiagnosticsSection(visibleRunStatus)
                        }
                        HeartbeatGlassSection {
                            HeartbeatHistoryDiagnosticsSection(diagnostics)
                            if (assistantStore.isGoodNightActive()) {
                                Text(
                                    stringResource(
                                        R.string.heartbeat_goodnight_on,
                                        assistantStore.goodNightNoUsageRuns(),
                                    ),
                                )
                            } else {
                                Text(stringResource(R.string.heartbeat_goodnight_off))
                            }
                        }
                        HeartbeatGlassSection {
                            Text(
                                text = stringResource(R.string.heartbeat_prompt_label),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            OutlinedTextField(
                                value = draft.heartbeatPrompt,
                                onValueChange = { draft = draft.copy(heartbeatPrompt = it) },
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.heartbeat_prompt_placeholder),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                    )
                                },
                                supportingText = { Text(stringResource(R.string.heartbeat_prompt_fixed_rule)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4,
                                maxLines = 10,
                                shape = RoundedCornerShape(18.dp),
                                colors = heartbeatTextFieldColors(),
                            )
                        }

                        HeartbeatGlassSection {
                            Text(
                                text = stringResource(R.string.heartbeat_runtime_settings_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            NumberField(
                                label = stringResource(R.string.heartbeat_min_interval),
                                value = draft.minIntervalMinutes,
                                onValueChange = { draft = draft.copy(minIntervalMinutes = it) },
                            )
                            NumberField(
                                label = stringResource(R.string.heartbeat_max_interval),
                                value = draft.maxIntervalMinutes,
                                onValueChange = { draft = draft.copy(maxIntervalMinutes = it) },
                            )
                            NumberField(
                                label = stringResource(R.string.heartbeat_timeout),
                                value = draft.generationTimeoutSeconds,
                                onValueChange = { draft = draft.copy(generationTimeoutSeconds = it) },
                            )
                            NumberField(
                                label = stringResource(R.string.heartbeat_max_steps),
                                value = draft.maxToolSteps,
                                onValueChange = { draft = draft.copy(maxToolSteps = it) },
                            )
                            if (draft.normalized() != draft) {
                                Text(
                                    text = stringResource(R.string.heartbeat_config_adjustment_warning),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        HeartbeatGlassSection {
                            ToggleRow(
                                label = stringResource(R.string.heartbeat_allow_low_risk_writes),
                                checked = draft.allowLowRiskWrites,
                                onCheckedChange = { draft = draft.copy(allowLowRiskWrites = it) },
                            )
                            Text(stringResource(R.string.heartbeat_tool_policy_note))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = {
                                    assistantStore.update(draft.copy(assistantId = currentAssistantId))
                                    HeartbeatScheduler.sync(this@HeartbeatSettingsActivity)
                                    if (draft.enabled) {
                                        requestExactAlarmPermissionIfNeeded()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.heartbeat_save))
                            }
                            OutlinedButton(
                                onClick = {
                                    assistantStore.update(draft.copy(assistantId = currentAssistantId))
                                    HeartbeatScheduler.triggerNow(
                                        context = this@HeartbeatSettingsActivity,
                                        assistantId = currentAssistantId,
                                    )
                                },
                                enabled = draft.enabled,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.heartbeat_trigger_now))
                            }
                        }
                        HeartbeatGlassSection {
                            Text(
                                text = stringResource(R.string.heartbeat_read_only_test_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.heartbeat_read_only_test_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    assistantStore.update(draft.copy(assistantId = currentAssistantId))
                                    HeartbeatScheduler.triggerReadOnlyTest(
                                        context = this@HeartbeatSettingsActivity,
                                        assistantId = currentAssistantId,
                                    )
                                },
                                enabled = draft.enabled &&
                                    visibleRunStatus.phase != HeartbeatRunPhase.QUEUED &&
                                    visibleRunStatus.phase != HeartbeatRunPhase.RUNNING,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.heartbeat_read_only_test))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        HeartbeatScheduler.sync(this)
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) return
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    override fun onDestroy() {
        activeAssistantStore?.close()
        super.onDestroy()
    }

}

@androidx.compose.runtime.Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@androidx.compose.runtime.Composable
private fun NumberField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(18.dp),
        colors = heartbeatTextFieldColors(),
    )
}

@Composable
private fun heartbeatTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)
