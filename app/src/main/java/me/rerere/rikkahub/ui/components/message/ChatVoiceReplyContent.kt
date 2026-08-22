package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.model.Assistant
import java.util.Locale

@Composable
internal fun ChatVoiceReplyContent(
    message: UIMessage,
    reply: UIMessageAnnotation.ChatVoiceReply,
    assistant: Assistant?,
    loading: Boolean,
    onTtsSpeak: ((String) -> Unit)?,
    onTranslateSegment: ((UIMessage, Int, String, Locale) -> Unit)?,
    onClearSegmentTranslation: ((UIMessage, Int) -> Unit)?,
) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        reply.segments.forEachIndexed { index, segment ->
            when (segment.type) {
                ChatVoiceReplySegmentType.TEXT -> {
                    ChatVoiceReplyTextSegment(
                        text = segment.text,
                        assistant = assistant,
                        loading = loading,
                        onTtsSpeak = onTtsSpeak,
                    )
                }

                ChatVoiceReplySegmentType.VOICE -> ChatVoiceMessageBubble(
                    segment = segment,
                    onTranslate = onTranslateSegment?.let { callback ->
                        { locale -> callback(message, index, segment.text, locale) }
                    },
                    onClearTranslation = onClearSegmentTranslation?.let { callback ->
                        { callback(message, index) }
                    },
                )
            }
        }
    }
}
