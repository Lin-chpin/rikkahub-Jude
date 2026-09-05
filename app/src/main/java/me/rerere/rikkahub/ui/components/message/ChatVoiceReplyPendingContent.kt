package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.ChatVoiceReplySegment
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.rikkahub.data.model.Assistant

@Composable
internal fun ChatVoiceReplyPendingContent(
    textSegments: List<ChatVoiceReplySegment>,
    assistant: Assistant?,
    loading: Boolean,
    onTtsSpeak: ((String) -> Unit)?,
) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        textSegments.forEach { segment ->
            if (segment.type == ChatVoiceReplySegmentType.TEXT) {
                ChatVoiceReplyTextSegment(
                    text = segment.text,
                    assistant = assistant,
                    loading = loading,
                    onTtsSpeak = onTtsSpeak,
                )
            }
        }
    }
}
