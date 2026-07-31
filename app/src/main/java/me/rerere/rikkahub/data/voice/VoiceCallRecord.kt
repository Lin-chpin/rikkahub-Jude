package me.rerere.rikkahub.data.voice

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.VoiceCallAudioSegment

data class VoiceCallCompletion(
    val callId: String,
    val durationSeconds: Int,
    val messageIds: Set<String>,
    val cardAnchorMessageId: String?,
    val audioSegmentsByMessageId: Map<String, List<VoiceCallAudioSegment>>,
)

fun UIMessage.voiceCallRecord(): UIMessageAnnotation.VoiceCallRecord? =
    annotations.filterIsInstance<UIMessageAnnotation.VoiceCallRecord>().firstOrNull()

fun UIMessage.withVoiceCallRecord(record: UIMessageAnnotation.VoiceCallRecord): UIMessage {
    return copy(
        annotations = annotations.filterNot { it is UIMessageAnnotation.VoiceCallRecord } + record
    )
}