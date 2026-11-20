package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

internal fun shouldOptimizeGeminiRelayCache(
    enabled: Boolean,
    provider: ProviderSetting,
    model: Model,
): Boolean = enabled &&
    provider is ProviderSetting.OpenAI &&
    !provider.useResponseApi &&
    model.modelId.contains("gemini", ignoreCase = true)

internal data class GeminiRelayCacheOverrides(
    val headers: List<CustomHeader>,
    val body: List<CustomBody>,
)

internal fun applyGeminiRelayCacheSessionId(
    headers: List<CustomHeader>,
    body: List<CustomBody>,
    sessionId: String?,
): GeminiRelayCacheOverrides {
    if (sessionId.isNullOrBlank()) return GeminiRelayCacheOverrides(headers, body)

    val updatedHeaders = headers.filterNot { it.name.equals("session-id", ignoreCase = true) } +
        CustomHeader("session-id", sessionId)
    val updatedBody = body.filterNot { it.key == "user" } + CustomBody("user", JsonPrimitive(sessionId))
    return GeminiRelayCacheOverrides(updatedHeaders, updatedBody)
}
