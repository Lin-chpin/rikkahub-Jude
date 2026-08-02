package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.voice.voiceCallRecord

internal fun Conversation.forNormalChatDisplay(): Conversation {
    val completedCallMessageIds = messageNodes
        .asSequence()
        .flatMap { it.messages.asSequence() }
        .mapNotNull { message ->
            message.voiceCallRecord()?.takeIf { it.standalone }
        }
        .flatMap { it.messageIds.asSequence() }
        .toSet()
    if (completedCallMessageIds.isEmpty()) return this

    return copy(
        // Keep call messages in storage for history playback, but project them out
        // of normal chat once the standalone record card owns their presentation.
        messageNodes = messageNodes.filterNot { node ->
            node.currentMessage.id.toString() in completedCallMessageIds
        },
    )
}
