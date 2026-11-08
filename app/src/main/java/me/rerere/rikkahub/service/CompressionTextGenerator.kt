package me.rerere.rikkahub.service

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.CompressOpenAIConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

internal class CompressionTextGenerator(
    providerManager: ProviderManager,
    private val settings: Settings,
) {
    private val model = settings.compressModelId?.let(settings::findModelById)
        ?: settings.getCurrentChatModel()
        ?: throw IllegalStateException("No model available for compression")
    private val provider = model.findProvider(settings.providers)
        ?: throw IllegalStateException("Provider not found")
    private val compressionProvider = provider.withCompressionApiOverride(settings.compressOpenAIConfig)
    private val compressionModel = model.withCompressionModelOverride(settings.compressOpenAIConfig)
    private val providerHandler = providerManager.getProviderByType(compressionProvider)

    suspend fun generate(
        content: String,
        targetTokens: Int,
        additionalContext: String,
    ): String? {
        val prompt = renderCompressionPrompt(
            settings = settings,
            content = content,
            targetTokens = targetTokens,
            additionalContext = additionalContext,
        )
        return providerHandler.generateText(
            providerSetting = compressionProvider,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(model = compressionModel),
        ).choices.firstOrNull()?.message?.toText()?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}

internal fun renderCompressionPrompt(
    settings: Settings,
    content: String,
    targetTokens: Int,
    additionalContext: String,
    locale: String = Locale.getDefault().displayName,
): String = settings.compressPrompt.applyPlaceholders(
    "content" to content,
    "target_tokens" to targetTokens.toString(),
    "additional_context" to additionalContext,
    "locale" to locale,
)

internal fun ProviderSetting.withCompressionApiOverride(
    config: CompressOpenAIConfig,
): ProviderSetting {
    if (!config.enabled) return this
    return ProviderSetting.OpenAI(
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        chatCompletionsPath = config.chatCompletionsPath,
        useResponseApi = config.useResponseApi,
    )
}

internal fun Model.withCompressionModelOverride(
    config: CompressOpenAIConfig,
): Model {
    if (!config.enabled || config.modelId.isBlank()) return this
    return copy(modelId = config.modelId.trim())
}
