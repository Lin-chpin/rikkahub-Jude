package me.rerere.rikkahub.data.voice

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ChatVoiceReplySegment
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Conversation
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

class ChatVoiceReplyMaterializer(
    private val audioGenerator: ChatVoiceReplyAudioGenerator,
) {
    suspend fun materialize(
        conversation: Conversation,
        generationBaseMessageIds: Set<Uuid>,
        onUpdate: (Conversation) -> Unit,
        settings: Settings,
    ) {
        val currentMessages = conversation.currentMessages
        val target = findChatVoiceReplyMaterializationTarget(
            messages = currentMessages,
            generationBaseMessageIds = generationBaseMessageIds,
        ) ?: run {
            val inspection = inspectChatVoiceReplyMaterialization(
                conversation = conversation,
                generationBaseMessageIds = generationBaseMessageIds,
            )
            inspection.latestNewToolMessageId?.let { toolMessageId ->
                onUpdate(
                    conversation.updateReplyMessage(toolMessageId) { message ->
                        message.withChatVoiceReplyToolError(
                            ChatVoiceReplyError(ChatVoiceReplyErrorCode.TOOL_FAILURE)
                        )
                    }
                )
            }
            return
        }
        val replyMessage = target.replyMessage
        val parsedReply = target.parsedReply

        if (target.toolError != null) {
            onUpdate(
                conversation.updateReplyMessage(replyMessage.id) { message ->
                    message.withChatVoiceReplyPlainText(parsedReply)
                }
            )
            return
        }

        val provider = settings.getSelectedTTSProvider()
        val configurationError = provider?.configurationError()
        if (provider == null || configurationError != null) {
            onUpdate(
                conversation.updateReplyMessage(replyMessage.id) { message ->
                    message
                        .withChatVoiceReplyPlainText(parsedReply)
                }
                    .updateChatVoiceReplyToolError(
                        target.toolMessageId,
                        configurationError ?: ChatVoiceReplyError(ChatVoiceReplyErrorCode.NO_MODEL),
                    )
            )
            return
        }

        var materialized = conversation.updateReplyMessage(replyMessage.id) { message ->
            message.withChatVoiceReply(parsedReply)
        }

        try {
            parsedReply.segments.mapIndexedNotNull { index, segment ->
                index.takeIf { segment.type == ChatVoiceReplySegmentType.VOICE }
            }.asReversed().forEach { segmentIndex ->
                val audioGroups = audioGenerator.generate(
                    text = parsedReply.segments[segmentIndex].text,
                    provider = provider,
                    englishOnly = settings.displaySetting.ttsEnglishOnly,
                )
                if (audioGroups.isEmpty()) {
                    throw ChatVoiceReplyGenerationException(ChatVoiceReplyErrorCode.EMPTY_AUDIO)
                }
                materialized = materialized.updateReplyMessage(replyMessage.id) { message ->
                    message.expandChatVoiceReplySegment(
                        segmentIndex = segmentIndex,
                        replacements = audioGroups.map { group ->
                            ChatVoiceReplySegment(
                                type = ChatVoiceReplySegmentType.VOICE,
                                text = group.text,
                                audioSegments = group.audioSegments,
                            )
                        },
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val voiceError = (error as? ChatVoiceReplyGenerationException)?.let { generationError ->
                ChatVoiceReplyError(generationError.errorCode)
            } ?: classifyChatVoiceReplyError(error)
            onUpdate(
                conversation.updateReplyMessage(replyMessage.id) { message ->
                    message
                        .withChatVoiceReplyPlainText(parsedReply)
                }
                    .updateChatVoiceReplyToolError(target.toolMessageId, voiceError)
            )
            return
        }
        onUpdate(materialized)
    }
}

private class ChatVoiceReplyGenerationException(
    val errorCode: ChatVoiceReplyErrorCode,
) : IllegalStateException()

internal data class ChatVoiceReplyMaterializationTarget(
    val replyMessage: UIMessage,
    val toolMessageId: Uuid,
    val parsedReply: ParsedChatVoiceReply,
    val toolError: ChatVoiceReplyError?,
    val usedProtocolFallback: Boolean = false,
)

private data class ChatVoiceReplyCandidate(
    val message: UIMessage,
    val parsedReply: ParsedChatVoiceReply,
    val usedProtocolFallback: Boolean,
)

internal data class ChatVoiceReplyMaterializationInspection(
    val voiceToolMessages: Int,
    val executedVoiceToolMessages: Int,
    val newExecutedVoiceToolMessages: Int,
    val latestExecutedToolIndex: Int?,
    val latestNewExecutedToolIndex: Int?,
    val latestNewToolMessageId: Uuid?,
    val assistantCandidatesAfterTool: Int,
    val markerCandidatesAfterTool: Int,
    val parseableCandidatesAfterTool: Int,
    val fallbackCandidatesAfterTool: Int,
    val usedProtocolFallback: Boolean,
    val matchedReplyMessageId: Uuid?,
)

internal fun ChatVoiceReplyMaterializationInspection.toDiagnosticDetails(): String = buildString {
    append("voiceToolMessages=")
    append(voiceToolMessages)
    append(" executedVoiceToolMessages=")
    append(executedVoiceToolMessages)
    append(" newExecutedVoiceToolMessages=")
    append(newExecutedVoiceToolMessages)
    append(" latestExecutedToolIndex=")
    append(latestExecutedToolIndex ?: "none")
    append(" latestNewExecutedToolIndex=")
    append(latestNewExecutedToolIndex ?: "none")
    append(" latestNewToolMessageId=")
    append(latestNewToolMessageId ?: "none")
    append(" assistantCandidatesAfterTool=")
    append(assistantCandidatesAfterTool)
    append(" markerCandidatesAfterTool=")
    append(markerCandidatesAfterTool)
    append(" parseableCandidatesAfterTool=")
    append(parseableCandidatesAfterTool)
    append(" fallbackCandidatesAfterTool=")
    append(fallbackCandidatesAfterTool)
    append(" usedProtocolFallback=")
    append(usedProtocolFallback)
    append(" matchedReplyMessageId=")
    append(matchedReplyMessageId ?: "none")
}

internal fun inspectChatVoiceReplyMaterialization(
    conversation: Conversation,
    generationBaseMessageIds: Set<Uuid>,
): ChatVoiceReplyMaterializationInspection {
    val messages = conversation.currentMessages
    val voiceToolEntries = messages.withIndex().filter { (_, message) ->
        message.parts.any { part ->
            part is UIMessagePart.Tool && part.toolName == CHAT_VOICE_REPLY_TOOL_NAME
        }
    }
    val executedVoiceToolEntries = voiceToolEntries.filter { (_, message) ->
        message.parts.any { part ->
            part is UIMessagePart.Tool &&
                part.toolName == CHAT_VOICE_REPLY_TOOL_NAME &&
                part.isExecuted
        }
    }
    val newExecutedVoiceToolEntries = executedVoiceToolEntries.filter { (_, message) ->
        message.id !in generationBaseMessageIds
    }
    val latestExecutedToolIndex = executedVoiceToolEntries.lastOrNull()?.index
    val latestNewExecutedToolEntry = newExecutedVoiceToolEntries.lastOrNull()
    val latestNewExecutedToolIndex = latestNewExecutedToolEntry?.index
    val candidates = latestNewExecutedToolIndex
        ?.let { messages.drop(it).asReversed() }
        .orEmpty()
    val assistantCandidates = candidates.filter { it.role == MessageRole.ASSISTANT }
    val markerCandidates = assistantCandidates.filter { message ->
        message.chatVoiceReplySourceText().contains("【语音条】")
    }
    val candidateMatches = assistantCandidates.mapNotNull(::chatVoiceReplyCandidate)
    val parsedCandidates = candidateMatches.count { !it.usedProtocolFallback }
    val fallbackCandidates = candidateMatches.count { it.usedProtocolFallback }
    val matchedReply = candidateMatches.firstOrNull()
    val target = matchedReply?.let { candidate ->
        ChatVoiceReplyMaterializationTarget(
            replyMessage = candidate.message,
            toolMessageId = requireNotNull(latestNewExecutedToolEntry).value.id,
            parsedReply = candidate.parsedReply,
            toolError = latestNewExecutedToolEntry?.value?.parts
                ?.filterIsInstance<UIMessagePart.Tool>()
                ?.lastOrNull { it.toolName == CHAT_VOICE_REPLY_TOOL_NAME }
                ?.chatVoiceReplyError(),
            usedProtocolFallback = candidate.usedProtocolFallback,
        )
    }

    return ChatVoiceReplyMaterializationInspection(
        voiceToolMessages = voiceToolEntries.size,
        executedVoiceToolMessages = executedVoiceToolEntries.size,
        newExecutedVoiceToolMessages = newExecutedVoiceToolEntries.size,
        latestExecutedToolIndex = latestExecutedToolIndex,
        latestNewExecutedToolIndex = latestNewExecutedToolIndex,
        latestNewToolMessageId = latestNewExecutedToolEntry?.value?.id,
        assistantCandidatesAfterTool = assistantCandidates.size,
        markerCandidatesAfterTool = markerCandidates.size,
        parseableCandidatesAfterTool = parsedCandidates,
        fallbackCandidatesAfterTool = fallbackCandidates,
        usedProtocolFallback = target?.usedProtocolFallback == true,
        matchedReplyMessageId = target?.replyMessage?.id,
    )
}

private fun chatVoiceReplyCandidate(message: UIMessage): ChatVoiceReplyCandidate? {
    if (message.role != MessageRole.ASSISTANT) return null
    val structuredReply = parseChatVoiceReply(message.chatVoiceReplySourceText())
    val parsedReply = structuredReply ?: parseChatVoiceReplyAsVoiceFallback(message.toText())
    return parsedReply?.let {
        ChatVoiceReplyCandidate(
            message = message,
            parsedReply = it,
            usedProtocolFallback = structuredReply == null,
        )
    }
}

internal fun findChatVoiceReplyMaterializationTarget(
    messages: List<UIMessage>,
    generationBaseMessageIds: Set<Uuid>,
): ChatVoiceReplyMaterializationTarget? {
    val toolMessageIndex = messages.indexOfLast { message ->
        message.id !in generationBaseMessageIds && message.parts.any { part ->
            part is UIMessagePart.Tool &&
                part.toolName == CHAT_VOICE_REPLY_TOOL_NAME &&
                part.isExecuted
        }
    }
    if (toolMessageIndex < 0) return null

    val candidates = messages
        .drop(toolMessageIndex)
        .asReversed()
    return candidates.firstNotNullOfOrNull { message ->
        chatVoiceReplyCandidate(message)?.let { candidate ->
            ChatVoiceReplyMaterializationTarget(
                replyMessage = candidate.message,
                toolMessageId = messages[toolMessageIndex].id,
                parsedReply = candidate.parsedReply,
                toolError = messages[toolMessageIndex].parts
                    .filterIsInstance<UIMessagePart.Tool>()
                    .lastOrNull { it.toolName == CHAT_VOICE_REPLY_TOOL_NAME }
                    ?.chatVoiceReplyError(),
                usedProtocolFallback = candidate.usedProtocolFallback,
            )
        }
    }
}

private fun Conversation.updateReplyMessage(
    messageId: Uuid,
    transform: (UIMessage) -> UIMessage,
): Conversation = copy(
    messageNodes = messageNodes.map { node ->
        if (node.messages.none { it.id == messageId }) {
            node
        } else {
            node.copy(
                messages = node.messages.map { message ->
                    if (message.id == messageId) transform(message) else message
                }
            )
        }
    }
)

private fun Conversation.updateChatVoiceReplyToolError(
    toolMessageId: Uuid,
    error: ChatVoiceReplyError,
): Conversation = updateReplyMessage(toolMessageId) { message ->
    message.withChatVoiceReplyToolError(error)
}
