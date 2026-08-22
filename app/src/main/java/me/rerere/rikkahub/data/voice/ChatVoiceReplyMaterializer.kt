package me.rerere.rikkahub.data.voice

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Conversation
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
        ) ?: return
        val replyMessage = target.replyMessage
        val parsedReply = target.parsedReply
        var materialized = conversation.updateReplyMessage(replyMessage.id) { message ->
            message.withChatVoiceReply(parsedReply)
        }

        settings.getSelectedTTSProvider()?.let { provider ->
            parsedReply.segments.forEachIndexed { segmentIndex, segment ->
                if (segment.type != ChatVoiceReplySegmentType.VOICE) return@forEachIndexed
                val audioSegments = audioGenerator.generate(
                    text = segment.text,
                    provider = provider,
                    englishOnly = settings.displaySetting.ttsEnglishOnly,
                )
                materialized = materialized.updateReplyMessage(replyMessage.id) { message ->
                    message.updateChatVoiceReplySegment(segmentIndex) { currentSegment ->
                        currentSegment.copy(audioSegments = audioSegments)
                    }
                }
            }
        }
        onUpdate(materialized)
    }
}

internal data class ChatVoiceReplyMaterializationTarget(
    val replyMessage: UIMessage,
    val parsedReply: ParsedChatVoiceReply,
)

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

    val candidates = buildList {
        add(messages[toolMessageIndex])
        addAll(messages.drop(toolMessageIndex + 1))
    }
    return candidates.firstNotNullOfOrNull { message ->
        if (message.role != MessageRole.ASSISTANT) return@firstNotNullOfOrNull null
        parseChatVoiceReply(message.toText())?.let { parsedReply ->
            ChatVoiceReplyMaterializationTarget(
                replyMessage = message,
                parsedReply = parsedReply,
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
