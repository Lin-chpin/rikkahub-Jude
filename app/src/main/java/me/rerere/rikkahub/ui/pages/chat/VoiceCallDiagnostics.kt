package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceCallDiagnosticsDialog(
    visible: Boolean,
    steps: List<String>,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排查流程（实时）") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (steps.isEmpty()) {
                    Text(
                        text = "暂时还没有流程记录。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    steps.forEach { step ->
                        Text(
                            text = step,
                            modifier = Modifier.padding(bottom = 6.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text("复制全部")
            }
        },
        dismissButton = {
            TextButton(onClick = onClear) {
                Text("清空")
            }
        },
    )
}
