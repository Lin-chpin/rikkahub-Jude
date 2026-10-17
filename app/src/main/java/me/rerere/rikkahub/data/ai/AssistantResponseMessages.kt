package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** Output-only boundary: a connected call must not append speech to the earlier invitation. */
internal fun beginAssistantResponse(
    messages: List<UIMessage>,
    modelId: Uuid,
    startNewMessage: Boolean,
): List<UIMessage> = if (startNewMessage) {
    messages + UIMessage(
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        modelId = modelId,
    )
} else {
    messages
}
