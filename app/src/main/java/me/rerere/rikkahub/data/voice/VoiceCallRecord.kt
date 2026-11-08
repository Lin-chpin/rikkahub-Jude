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

fun Conversation.standaloneVoiceCallRecord(callId: String): UIMessageAnnotation.VoiceCallRecord? =
    messageNodes.asSequence()
        .mapNotNull { it.currentMessage.voiceCallRecord() }
        .firstOrNull { it.standalone && it.callId == callId }

fun Conversation.withStandaloneVoiceCallSummary(
    callId: String,
    summary: String,
): Conversation? {
    val trimmedSummary = summary.trim()
    if (trimmedSummary.isBlank()) return null

    var updated = false
    val updatedNodes = messageNodes.map { node ->
        if (node.currentMessage.voiceCallRecord()?.let { it.standalone && it.callId == callId } != true) {
            node
        } else {
            node.copy(
                messages = node.messages.map { message ->
                    message.copy(
                        annotations = message.annotations.map { annotation ->
                            if (annotation is UIMessageAnnotation.VoiceCallRecord &&
                                annotation.standalone && annotation.callId == callId
                            ) {
                                updated = true
                                annotation.copy(summary = trimmedSummary)
                            } else annotation
                        }
                    )
                }
            )
        }
    }
    return if (updated) copy(messageNodes = updatedNodes) else null
}

fun Conversation.voiceCallSummaryRecordsForGeneration(
    messageRange: ClosedRange<Int>? = null,
): List<UIMessageAnnotation.VoiceCallRecord> {
    val sourceNodes = messageRange?.let { messageNodes.subList(it.start, it.endInclusive + 1) } ?: messageNodes
    return sourceNodes.mapNotNull { node ->
        node.currentMessage.voiceCallRecord()?.takeIf {
            it.standalone && !it.summary.isNullOrBlank()
        }
    }
}

fun Conversation.voiceCallSummaryPrompt(messageRange: ClosedRange<Int>? = null): String? {
    val records = voiceCallSummaryRecordsForGeneration(messageRange)
    if (records.isEmpty()) return null
    return buildString {
        append("The following are summaries of completed voice calls. They replace the original messages from those calls. ")
        append("Use them as conversation history, not as new user requests.")
        records.forEachIndexed { index, record ->
            appendLine()
            appendLine()
            append("Call ${index + 1}: ")
            append(record.summary.orEmpty().trim())
        }
    }
}

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
