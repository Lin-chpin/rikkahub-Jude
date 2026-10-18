package me.rerere.rikkahub.personal.heartbeat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import android.graphics.drawable.Drawable
import me.rerere.usagetracker.AppIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R

@Composable
internal fun SentinelBlockedAppsDialog(
    blockedPackages: Set<String>, vm: SentinelSetupViewModel,
    onSave: (Set<String>) -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    val failed by vm.appListFailed.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(blockedPackages) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadApps(context) }
    val entries = remember(apps, query, blockedPackages) {
        (apps.orEmpty() + blockedPackages.filter { it !in apps.orEmpty() }.associateWith { it })
            .entries.filter { it.key.contains(query, true) || it.value.contains(query, true) }
            .sortedWith(compareByDescending<Map.Entry<String, String>> { it.key in blockedPackages }.thenBy { it.value.lowercase() })
    }
    val duplicateLabels = remember(apps) { apps.orEmpty().values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sentinel_blocked_apps_title)) },
        text = {
            Column {
                Text(stringResource(R.string.sentinel_blocked_apps_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.sentinel_blocked_apps_search)) }, modifier = Modifier.fillMaxWidth())
                if (failed) {
                    Text(stringResource(R.string.sentinel_app_list_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.loadApps(context) }) { Text(stringResource(R.string.sentinel_app_list_retry)) }
                } else if (apps == null) CircularProgressIndicator()
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(entries, key = { it.key }) { (packageName, label) ->
                        val checked = packageName in selected
                        val icon by produceState<Drawable?>(initialValue = null, key1 = packageName) {
                            value = vm.loadAppIcon(context, packageName)
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, role = Role.Checkbox, onValueChange = { enabled ->
                            selected = if (enabled) selected + packageName else selected - packageName
                        }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppIcon(icon, label)
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                if (label in duplicateLabels) Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected); onDismiss() }) { Text(stringResource(R.string.heartbeat_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
