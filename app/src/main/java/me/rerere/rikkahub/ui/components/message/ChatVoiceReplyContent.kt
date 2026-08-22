package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.context.LocalSettings
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
    val settings = LocalSettings.current.displaySetting
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        reply.segments.forEachIndexed { index, segment ->
            when (segment.type) {
                ChatVoiceReplySegmentType.TEXT -> {
                    val content = segment.text.replaceRegexes(
                        assistant = assistant,
                        scope = AssistantAffectScope.ASSISTANT,
                        visual = true,
                    )
                    val textContent: @Composable () -> Unit = {
                        AssistantTextContent(
                            content = content,
                            onClickCitation = {},
                            selectionEnabled = !loading,
                            showElevenLabsAudioTagAnnotations = false,
                            showParagraphTtsButtons = settings.showParagraphTtsButtons,
                            paragraphBubbleMode = assistant?.momentsChatStyle == true,
                            onTtsSpeak = onTtsSpeak,
                        )
                    }
                    if (settings.showAssistantBubble && assistant?.momentsChatStyle != true) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(Modifier.padding(8.dp)) { textContent() }
                        }
                    } else {
                        textContent()
                    }
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
