package me.rerere.rikkahub.personal.heartbeat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.components.message.ChatMessageNerdLine

@Composable
internal fun HeartbeatResponseStats(response: UIMessage, title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
    Text(response.createdAt.toString(), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    ChatMessageNerdLine(response, color = MaterialTheme.colorScheme.onSurfaceVariant, showTokenUsage = true)
}
