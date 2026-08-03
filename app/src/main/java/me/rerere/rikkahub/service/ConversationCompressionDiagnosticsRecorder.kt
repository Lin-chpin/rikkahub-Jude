package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.model.Conversation
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
    }
}
