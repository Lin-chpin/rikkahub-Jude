package me.rerere.rikkahub.local

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant

/**
 * Optional provider and assistant overrides for a local conversation-mode turn.
 * The public build leaves this unset; personal builds can route special turns
 * such as English explanations to their dedicated model.
 */
data class LocalConversationModeGenerationConfig(
    val model: Model,
    val assistant: Assistant,
    val providerOverride: ProviderSetting? = null,
)
