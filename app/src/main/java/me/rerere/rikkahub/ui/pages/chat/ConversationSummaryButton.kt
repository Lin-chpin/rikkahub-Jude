package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.Tooltip
import androidx.compose.ui.res.stringResource

@Composable
fun ConversationSummaryButton(
    summary: String,
    autoCompressEnabled: Boolean,
    onSummaryChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onEditorVisibilityChange: (Boolean) -> Unit = {},
    title: String = "滚动摘要",
    statusLabel: String? = null,
    allowClear: Boolean = true,
) {
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var editText by rememberSaveable(summary) {
        mutableStateOf(summary)
    }

    LaunchedEffect(editorVisible) {
        onEditorVisibilityChange(editorVisible)
    }
    DisposableEffect(Unit) {
        onDispose {
            onEditorVisibilityChange(false)
        }
    }

    Tooltip(
        tooltip = {
            Text(title)
        }
    ) {
        IconButton(
            onClick = {
                editText = summary
                editorVisible = true
            },
            modifier = modifier,
        ) {
            Icon(
                imageVector = HugeIcons.FileZip,
                contentDescription = title,
            )
        }
    }

    if (editorVisible) {
        AlertDialog(
            onDismissRequest = { editorVisible = false },
            modifier = Modifier.imePadding(),
            title = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title)
                    Text(
                        text = statusLabel ?: if (autoCompressEnabled) "自动更新" else "手动摘要",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 460.dp),
                    label = { Text("摘要内容") },
                    minLines = 10,
                    maxLines = 24,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = allowClear || editText.isNotBlank(),
                    onClick = {
                        onSummaryChange(editText.ifBlank { null })
                        editorVisible = false
                    },
                ) {
                    Text(stringResource(R.string.chat_page_conversation_system_prompt_save))
                }
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (allowClear) {
                        TextButton(
                            onClick = {
                                editText = ""
                                onSummaryChange(null)
                                editorVisible = false
                            },
                        ) {
                            Text(stringResource(R.string.chat_page_conversation_system_prompt_clear))
                        }
                    }
                    TextButton(
                        onClick = { editorVisible = false },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }
}
