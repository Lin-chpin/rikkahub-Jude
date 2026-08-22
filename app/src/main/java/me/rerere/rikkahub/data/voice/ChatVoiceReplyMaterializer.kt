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
        val toolMessageIndex = currentMessages.indexOfLast { message ->
            message.id !in generationBaseMessageIds && message.parts.any { part ->
                part is UIMessagePart.Tool &&
                    part.toolName == CHAT_VOICE_REPLY_TOOL_NAME &&
                    part.isExecuted
            }
        }
        if (toolMessageIndex < 0) return

        val replyMessage = currentMessages
            .drop(toolMessageIndex + 1)
            .firstOrNull { it.role == MessageRole.ASSISTANT && it.toText().isNotBlank() }
            ?: return
        val parsedReply = parseChatVoiceReply(replyMessage.toText()) ?: return
        val toolMessageId = currentMessages[toolMessageIndex].id
        var materialized = conversation.copy(
            messageNodes = conversation.messageNodes.mapNotNull { node ->
                when {
                    node.currentMessage.id == toolMessageId -> null
                    node.messages.any { it.id == replyMessage.id } -> node.copy(
                        messages = node.messages.map { message ->
                            if (message.id == replyMessage.id) {
                                message.withChatVoiceReply(parsedReply)
                            } else {
                                message
                            }
                        }
                    )
                    else -> node
                }
            }
        )
        onUpdate(materialized)

        val provider = settings.getSelectedTTSProvider() ?: return
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
            onUpdate(materialized)
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
