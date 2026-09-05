package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.voice.CHAT_VOICE_REPLY_TOOL_NAME
import me.rerere.rikkahub.data.voice.chatVoiceReplyError
import java.util.Locale

@Composable
internal fun ChatVoiceReplyMessageContent(
    message: UIMessage,
    reply: UIMessageAnnotation.ChatVoiceReply,
    assistant: Assistant?,
    model: Model?,
    loading: Boolean,
    onTtsSpeak: ((String) -> Unit)?,
    onTranslateSegment: ((UIMessage, Int, String, Locale) -> Unit)?,
    onClearSegmentTranslation: ((UIMessage, Int) -> Unit)?,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)?,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val supportingParts = message.parts.filterNot { part ->
            part is UIMessagePart.Text ||
                (part is UIMessagePart.Tool &&
                    part.toolName == CHAT_VOICE_REPLY_TOOL_NAME &&
                    part.chatVoiceReplyError() == null)
        }
        if (supportingParts.isNotEmpty()) {
            MessagePartsBlock(
                assistant = assistant,
                role = message.role,
                model = model,
                parts = supportingParts,
                annotations = message.annotations,
                loading = loading,
                showElevenLabsAudioTagAnnotations = false,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
            )
        }
        ChatVoiceReplyContent(
            message = message,
            reply = reply,
            assistant = assistant,
            loading = loading,
            onTtsSpeak = onTtsSpeak,
            onTranslateSegment = onTranslateSegment,
            onClearSegmentTranslation = onClearSegmentTranslation,
        )
    }
}
