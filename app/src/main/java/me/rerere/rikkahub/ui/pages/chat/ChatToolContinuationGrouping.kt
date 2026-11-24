package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode

internal data class ChatMessageDisplayItem(
    val node: MessageNode,
    val sourceNodes: List<MessageNode>,
    val showActions: Boolean,
    val showGenerationSpeed: Boolean,
)

internal fun List<MessageNode>.groupAssistantToolContinuations(): List<ChatMessageDisplayItem> {
    val result = mutableListOf<ChatMessageDisplayItem>()
    var index = 0
    while (index < size) {
        val firstNode = this[index]
        val firstMessage = firstNode.currentMessage
        if (!firstMessage.isToolOnlyAssistantMessage()) {
            result += ChatMessageDisplayItem(
                firstNode,
                listOf(firstNode),
                showActions = true,
                showGenerationSpeed = true,
            )
            index++
            continue
        }

        val continuationNodes = mutableListOf(firstNode)
        index++
        while (index < size) {
            val candidate = this[index]
            val message = candidate.currentMessage
            if (message.modelId != firstMessage.modelId || !message.isToolOnlyAssistantMessage()) break
            continuationNodes += candidate
            index++
        }

        if (continuationNodes.size == 1) {
            result += ChatMessageDisplayItem(
                firstNode,
                continuationNodes,
                showActions = true,
                showGenerationSpeed = true,
            )
        } else {
            val messages = continuationNodes.map { it.currentMessage }
            val combinedMessage = firstMessage.copy(
                parts = messages.flatMap { it.parts },
                annotations = messages.flatMap { it.annotations }.distinct(),
                finishedAt = messages.last().finishedAt,
                translation = null,
                usage = messages.aggregateUsage(),
            )
            val displayNode = MessageNode(
                id = firstNode.id,
                messages = listOf(combinedMessage),
                selectIndex = 0,
                isFavorite = continuationNodes.any { it.isFavorite },
            )
            result += ChatMessageDisplayItem(
                displayNode,
                continuationNodes,
                showActions = false,
                showGenerationSpeed = false,
            )
        }
    }
    return result
}

private fun List<UIMessage>.aggregateUsage(): TokenUsage? {
    if (any { it.usage == null }) return null
    val usages = mapNotNull { it.usage }
    return TokenUsage(
        promptTokens = usages.sumOf { it.promptTokens },
        completionTokens = usages.sumOf { it.completionTokens },
        cachedTokens = usages.sumOf { it.cachedTokens },
        cachedTokensReported = usages.all { it.cachedTokensReported },
        totalTokens = usages.sumOf { usage ->
            usage.totalTokens.takeIf { it > 0 } ?: usage.promptTokens + usage.completionTokens
        },
    )
}

private fun UIMessage.isToolOnlyAssistantMessage(): Boolean =
    role == MessageRole.ASSISTANT &&
        modelId != null &&
        parts.any { it is UIMessagePart.Tool } &&
        parts.all { part ->
            when (part) {
                is UIMessagePart.Tool, is UIMessagePart.Reasoning -> true
                is UIMessagePart.Text -> part.text.isBlank()
                else -> false
            }
        }
