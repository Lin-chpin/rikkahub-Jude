package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.voice.CHAT_VOICE_REPLY_TOOL_NAME
import me.rerere.rikkahub.data.voice.chatVoiceReply
import me.rerere.rikkahub.data.voice.chatVoiceReplyDraft
import me.rerere.rikkahub.data.voice.chatVoiceReplyError
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val MAX_COMPRESSION_DIAGNOSTIC_STEPS = 300

/**
 * Keeps privacy-safe, process-local state transitions for compression troubleshooting.
 * Message content and summary text are intentionally excluded from the report.
 */
internal class ConversationCompressionDiagnosticsRecorder {
    private class DiagnosticSession(
        @Volatile var startedAtMillis: Long = System.currentTimeMillis(),
        val steps: MutableStateFlow<List<String>> = MutableStateFlow(emptyList()),
    )

    private val sessions = ConcurrentHashMap<Uuid, DiagnosticSession>()

    fun observe(conversationId: Uuid): StateFlow<List<String>> =
        session(conversationId).steps.asStateFlow()

    fun record(
        conversationId: Uuid,
        stage: String,
        conversation: Conversation,
        details: String? = null,
    ) {
        val diagnosticSession = session(conversationId)
        val elapsedMillis = (System.currentTimeMillis() - diagnosticSession.startedAtMillis)
            .coerceAtLeast(0L)
        val entry = buildString {
            append("[+")
            append(elapsedMillis)
            append("ms] ")
            append("stage=")
            append(stage)
            append(' ')
            append(conversation.compressionDiagnosticSnapshot())
            details?.takeIf { it.isNotBlank() }?.let {
                append(' ')
                append(it)
            }
        }
        diagnosticSession.steps.update { current ->
            (current + entry).takeLast(MAX_COMPRESSION_DIAGNOSTIC_STEPS)
        }
    }

    fun clear(conversationId: Uuid) {
        session(conversationId).apply {
            startedAtMillis = System.currentTimeMillis()
            steps.value = emptyList()
        }
    }

    private fun session(conversationId: Uuid): DiagnosticSession =
        sessions.computeIfAbsent(conversationId) { DiagnosticSession() }
}

private fun Conversation.compressionDiagnosticSnapshot(): String {
    val existingNodeIds = messageNodes.mapTo(mutableSetOf()) { it.id }
    val validStoredCompressedCount = compressedMessageNodeIds.count { it in existingNodeIds }
    val danglingCompressedCount = compressedMessageNodeIds.size - validStoredCompressedCount
    val allNodesCovered = messageNodes.isNotEmpty() &&
        validStoredCompressedCount >= messageNodes.size

    return buildString {
        append("conversation=")
        append(id)
        append(" nodes=")
        append(messageNodes.size)
        append(" variants=")
        append(messageNodes.sumOf { it.messages.size })
        append(" visible=")
        append(visibleMessageNodes.size)
        append(" storedCompressed=")
        append(compressedMessageNodeIds.size)
        append(" activeCompressed=")
        append(activeCompressedMessageNodeIds.size)
        append(" danglingCompressed=")
        append(danglingCompressedCount)
        append(" allNodesCovered=")
        append(allNodesCovered)
        append(" summaryChars=")
        append(compressedSummary?.length ?: 0)
        append(' ')
        append(currentMessages.chatVoiceReplyDiagnosticSnapshot())
    }
}

/**
 * Adds voice-reply lifecycle state without recording reply text or provider details.
 * This is intentionally part of every compression diagnostic snapshot so the chat
 * troubleshooting dialog can be copied at any point in generation.
 */
private fun List<UIMessage>.chatVoiceReplyDiagnosticSnapshot(): String {
    val voiceTools = flatMap { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolName == CHAT_VOICE_REPLY_TOOL_NAME }
    }
    val voiceToolErrors = voiceTools.mapNotNull { it.chatVoiceReplyError()?.code?.name }
    val replies = count { it.chatVoiceReply() != null }
    val drafts = count { it.chatVoiceReplyDraft() != null }
    val voiceSegments = flatMap { it.chatVoiceReply()?.segments.orEmpty() }
        .count { it.type == ChatVoiceReplySegmentType.VOICE }
    val audioSegments = flatMap { it.chatVoiceReply()?.segments.orEmpty() }
        .filter { it.type == ChatVoiceReplySegmentType.VOICE }
        .sumOf { it.audioSegments.size }
    val emptyAudioSegments = flatMap { it.chatVoiceReply()?.segments.orEmpty() }
        .count { it.type == ChatVoiceReplySegmentType.VOICE && it.audioSegments.isEmpty() }

    return buildString {
        append("voiceTools=")
        append(voiceTools.size)
        append(" voiceToolExecuted=")
        append(voiceTools.count { it.isExecuted })
        append(" voiceToolPending=")
        append(voiceTools.count { !it.isExecuted })
        append(" voiceToolErrors=")
        append(voiceToolErrors.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "none")
        append(" voiceDrafts=")
        append(drafts)
        append(" voiceReplies=")
        append(replies)
        append(" voiceSegments=")
        append(voiceSegments)
        append(" voiceAudioSegments=")
        append(audioSegments)
        append(" voiceEmptyAudioSegments=")
        append(emptyAudioSegments)
    }
}
