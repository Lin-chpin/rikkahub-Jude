package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.SYSTEM_PROMPT_DYNAMIC_SECTION
import me.rerere.ai.ui.SYSTEM_PROMPT_SECTION_METADATA_KEY
import me.rerere.ai.core.MessageRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
