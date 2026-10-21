package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** Starts a new assistant output message for connected calls and Google tool steps. */
internal fun beginAssistantResponse(
    messages: List<UIMessage>,
    modelId: Uuid,
    startNewMessage: Boolean,
    providerSetting: ProviderSetting? = null,
): List<UIMessage> {
    val previousAssistantTools = messages.lastOrNull()
        ?.takeIf { it.role == MessageRole.ASSISTANT }
        ?.getTools()
    val beginsGoogleToolContinuation = providerSetting is ProviderSetting.Google &&
        !previousAssistantTools.isNullOrEmpty() && previousAssistantTools.all { it.isExecuted }

    return if (startNewMessage || beginsGoogleToolContinuation) {
        messages + UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            modelId = modelId,
        )
    } else {
        messages
    }
}
