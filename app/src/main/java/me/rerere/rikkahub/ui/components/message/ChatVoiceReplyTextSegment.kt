package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
internal fun ChatVoiceReplyTextSegment(
    text: String,
    assistant: Assistant?,
    loading: Boolean,
    onTtsSpeak: ((String) -> Unit)?,
) {
    val settings = LocalSettings.current.displaySetting
    val content = text.replaceRegexes(
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
            color = assistantMessageBubbleColor(),
        ) {
            Column(Modifier.padding(8.dp)) { textContent() }
        }
    } else {
        textContent()
    }
}
