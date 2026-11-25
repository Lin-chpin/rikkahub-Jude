package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.SYSTEM_PROMPT_DYNAMIC_SECTION
import me.rerere.ai.ui.SYSTEM_PROMPT_SECTION_METADATA_KEY
import me.rerere.ai.core.MessageRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Captured call frames belong to this request, never to the persisted user message.
internal fun List<UIMessage>.withRequestMessageParts(parts: List<UIMessagePart>): List<UIMessage> {
    if (parts.isEmpty()) return this
    val userIndex = indexOfLast { it.role == MessageRole.USER }
    require(userIndex >= 0) { "当前请求没有可附加画面的用户消息" }
    return mapIndexed { index, message ->
        if (index == userIndex) message.copy(parts = message.parts + parts) else message
    }
}

internal suspend fun List<UIMessagePart>.onlyWhile(enabled: suspend () -> Boolean): List<UIMessagePart> =
    if (isEmpty() || enabled()) this else emptyList()

// Transient per-turn runtime instructions attach to a request-only copy of the last USER message
// so prior history and tool-continuation prefixes stay cacheable without mutating stored messages.
internal fun List<UIMessage>.withRuntimeUserContext(text: String?): List<UIMessage> {
    if (text.isNullOrBlank()) return this
    val userIndex = indexOfLast { it.role == MessageRole.USER }
    if (userIndex < 0) return withRuntimeContextInstruction(text)
    return mapIndexed { index, message ->
        if (index == userIndex) {
            message.copy(parts = message.parts + UIMessagePart.Text(text))
        } else {
            message
        }
    }
}

internal fun List<UIMessage>.withRuntimeContextInstruction(text: String?): List<UIMessage> =
    if (text.isNullOrBlank()) this else this + UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(UIMessagePart.Text(
            text = text,
            metadata = buildJsonObject {
                put(SYSTEM_PROMPT_SECTION_METADATA_KEY, SYSTEM_PROMPT_DYNAMIC_SECTION)
            },
        )),
    )
