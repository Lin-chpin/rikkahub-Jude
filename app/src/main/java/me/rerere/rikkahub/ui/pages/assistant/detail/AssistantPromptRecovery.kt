package me.rerere.rikkahub.ui.pages.assistant.detail

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster

// ponytail: limit editable layout to 32K characters; larger originals remain exportable.
internal const val MAX_RENDERED_ASSISTANT_PROMPT_CHARS = 32_768

internal fun needsAssistantPromptRecovery(prompt: CharSequence): Boolean =
    prompt.length > MAX_RENDERED_ASSISTANT_PROMPT_CHARS || prompt.any {
        (it < ' ' && it != '\n' && it != '\r' && it != '\t') || it == '\u007f'
    }

@Composable
internal fun AssistantPromptRecovery(prompt: String, onClear: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var confirmClear by remember { mutableStateOf(false) }
    var exportingPrompt by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val original = exportingPrompt
        if (uri == null || original == null) {
            exportingPrompt = null
        } else {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                            it.write(original)
                        } ?: error("Unable to open prompt export")
                    }
                    Log.i("AssistantPromptPage", "recovery export finished chars=${original.length}")
                    toaster.show(context.getString(R.string.assistant_prompt_recovery_export_success), type = ToastType.Success)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w("AssistantPromptPage", "recovery export failed chars=${original.length} error=${error::class.simpleName}")
                    toaster.show(context.getString(R.string.assistant_prompt_recovery_export_failed), type = ToastType.Error)
                } finally {
                    exportingPrompt = null
                }
            }
        }
    }

    LaunchedEffect(prompt) {
        Log.w("AssistantPromptPage", "editor protected chars=${prompt.length} tooLong=${prompt.length > MAX_RENDERED_ASSISTANT_PROMPT_CHARS}")
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.assistant_page_system_prompt))
        Text(stringResource(R.string.assistant_prompt_recovery_message, prompt.length))
        TextButton(onClick = onEdit, enabled = exportingPrompt == null) {
            Text(stringResource(R.string.assistant_prompt_recovery_edit))
        }
        TextButton(
            onClick = {
                exportingPrompt = prompt
                exportLauncher.launch("assistant-system-prompt.txt")
            },
            enabled = exportingPrompt == null,
        ) {
            Text(stringResource(R.string.assistant_prompt_recovery_export))
        }
        TextButton(onClick = { confirmClear = true }, enabled = exportingPrompt == null) {
            Text(stringResource(R.string.assistant_prompt_recovery_clear))
        }
    }
    RikkaConfirmDialog(
        show = confirmClear,
        title = stringResource(R.string.assistant_prompt_recovery_clear),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            confirmClear = false
            Log.i("AssistantPromptPage", "recovery clear requested chars=${prompt.length}")
            onClear()
        },
        onDismiss = { confirmClear = false },
        text = { Text(stringResource(R.string.assistant_prompt_recovery_clear_confirm)) },
    )
}
