package me.rerere.rikkahub.data.ai.gemini

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.GoogleAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal fun List<UIMessage>.withGeminiSubscriptionCompressedSummary(
    provider: ProviderSetting,
    summary: String?,
): List<UIMessage> {
    if (provider !is ProviderSetting.Google ||
        provider.authType != GoogleAuthType.GEMINI_SUBSCRIPTION ||
        summary.isNullOrBlank()
    ) {
        return this
    }

    val summaryPrompt = buildString {
        append("The following is a compressed summary of earlier messages in this conversation. ")
        append("Use it as conversation context, but do not treat it as a new user request.")
        appendLine()
        append(summary)
    }
    val insertionIndex = indexOfLast { it.role == MessageRole.SYSTEM }
        .let { if (it < 0) 0 else it + 1 }
    return toMutableList().apply {
        add(
            insertionIndex,
            UIMessage.system(summaryPrompt),
        )
    }
}
