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

internal fun addGeminiRelayCacheSessionId(
    headers: List<CustomHeader>,
    body: List<CustomBody>,
    sessionId: String?,
): GeminiRelayCacheOverrides {
    if (sessionId.isNullOrBlank()) return GeminiRelayCacheOverrides(headers, body)

    val updatedHeaders = if (headers.any { it.name.equals("session-id", ignoreCase = true) }) {
        headers
    } else {
        headers + CustomHeader("session-id", sessionId)
    }
    val updatedBody = if (body.any { it.key == "user" }) {
        body
    } else {
        body + CustomBody("user", JsonPrimitive(sessionId))
    }
    return GeminiRelayCacheOverrides(updatedHeaders, updatedBody)
}
