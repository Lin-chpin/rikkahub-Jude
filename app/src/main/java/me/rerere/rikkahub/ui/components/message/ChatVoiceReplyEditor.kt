package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.ChatVoiceReplySegment
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.R

@Composable
internal fun ChatVoiceReplyEditor(
    reply: UIMessageAnnotation.ChatVoiceReply,
    onSave: (List<ChatVoiceReplySegment>) -> Unit,
    onDismiss: () -> Unit,
) {
    var segments by remember { mutableStateOf(reply.segments) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_voice_reply_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                segments.forEachIndexed { index, segment ->
                    if (segment.type == ChatVoiceReplySegmentType.TEXT) {
                        OutlinedTextField(
                            value = segment.text,
                            onValueChange = { text ->
                                segments = segments.mapIndexed { i, value -> if (i == index) value.copy(text = text) else value }
                            },
                            label = { Text(stringResource(R.string.chat_voice_reply_text_segment)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.chat_voice_reply_voice_preserved), style = MaterialTheme.typography.labelMedium)
                        Text(segment.text)
                    }
                    TextButton(onClick = { segments = segments.filterIndexed { i, _ -> i != index } }) {
                        Text(stringResource(R.string.chat_voice_reply_delete_segment))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(segments) }, enabled = segments.any { it.text.isNotBlank() }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
