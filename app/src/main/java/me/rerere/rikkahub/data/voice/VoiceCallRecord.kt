package me.rerere.rikkahub.data.voice

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.VoiceCallAudioSegment
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

data class VoiceCallEndedEventConsumption(
    val conversation: Conversation,
    val shouldNotifyModel: Boolean,
)

data class VoiceCallCompletion(
    val callId: String,
    val durationSeconds: Int,
    val messageIds: Set<String>,
    val audioSegmentsByMessageId: Map<String, List<VoiceCallAudioSegment>>,
)

fun UIMessage.voiceCallRecord(): UIMessageAnnotation.VoiceCallRecord? =
    annotations.filterIsInstance<UIMessageAnnotation.VoiceCallRecord>().firstOrNull()

fun UIMessage.isStandaloneVoiceCallRecord(): Boolean =
    voiceCallRecord()?.standalone == true

fun Conversation.voiceCallRecordNodeIdsFullyCoveredBy(
    coveredNodeIds: Set<Uuid>,
): Set<Uuid> {
    val existingMessageIds = messageNodes
        .asSequence()
        .flatMap { it.messages.asSequence() }
        .mapTo(mutableSetOf()) { it.id.toString() }
    val coveredMessageIds = messageNodes
        .asSequence()
        .filter { it.id in coveredNodeIds }
        .flatMap { it.messages.asSequence() }
        .mapTo(mutableSetOf()) { it.id.toString() }

    return messageNodes.mapNotNullTo(mutableSetOf()) { node ->
        val record = node.currentMessage.voiceCallRecord()?.takeIf { it.standalone }
            ?: return@mapNotNullTo null
        val linkedExistingIds = record.messageIds.filterTo(mutableSetOf()) {
            it in existingMessageIds
        }
        node.id.takeIf {
            linkedExistingIds.isNotEmpty() && linkedExistingIds.all { it in coveredMessageIds }
        }
    }
}

fun Conversation.consumePendingVoiceCallEndedEvent(): VoiceCallEndedEventConsumption {
    var shouldNotifyModel = false
    val updatedNodes = messageNodes.map { node ->
        node.copy(
            messages = node.messages.map { message ->
                message.copy(
                    annotations = message.annotations.map { annotation ->
                        if (annotation is UIMessageAnnotation.VoiceCallRecord &&
                            annotation.standalone && annotation.pendingEndedEvent
                        ) {
                            shouldNotifyModel = true
                            annotation.copy(pendingEndedEvent = false)
                        } else {
                            annotation
                        }
                    }
                )
            }
        )
    }
    return VoiceCallEndedEventConsumption(
        conversation = if (shouldNotifyModel) copy(messageNodes = updatedNodes) else this,
        shouldNotifyModel = shouldNotifyModel,
    )
}
