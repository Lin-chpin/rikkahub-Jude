package me.rerere.rikkahub.data.ai.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.math.roundToInt

internal data class OpenAICodexQuotaWindow(
    val displayName: String,
    val remainingPercent: Int?,
    val resetTime: String?,
)

internal fun parseOpenAICodexQuota(root: JsonObject): List<OpenAICodexQuotaWindow> {
    val result = mutableListOf<OpenAICodexQuotaWindow>()
    val mainRateLimit = root.obj("rate_limit")
        ?: root.obj("rate_limits")?.obj("rate_limit")
    mainRateLimit?.let { rateLimit ->
        rateLimit.window("primary_window", "主要额度", result)
        rateLimit.window("secondary_window", "次要额度", result)
    }

    val additional = root.array("additional_rate_limits")
        ?: root.obj("rate_limits")?.array("additional_rate_limits")
    additional.orEmpty().forEach { value ->
        val entry = value as? JsonObject ?: return@forEach
        val rateLimit = entry.obj("rate_limit") ?: entry
        val name = entry.string("display_name")
            .ifBlank { entry.string("name") }
            .ifBlank { entry.string("limit_name") }
            .ifBlank { "额外额度" }
        rateLimit.window("primary_window", name, result)
        rateLimit.window("secondary_window", name, result)
    }
    return result
}

private fun JsonObject.window(
    key: String,
    fallbackName: String,
    output: MutableList<OpenAICodexQuotaWindow>,
) {
    val window = obj(key) ?: return
    val usedPercent = window.number("used_percent")
    val resetTime = window.resetTime()
    val duration = window.number("limit_window_seconds")?.roundToInt()
    val label = duration?.let(::formatWindowLength)?.let { "$fallbackName（$it）" } ?: fallbackName
    output += OpenAICodexQuotaWindow(
        displayName = label,
        remainingPercent = usedPercent?.let { (100.0 - it).roundToInt().coerceIn(0, 100) },
        resetTime = resetTime,
    )
}

private fun JsonObject.resetTime(): String? {
    val resetAt = this["reset_at"]
    val epochSeconds = (resetAt as? JsonPrimitive)?.longOrNull
    if (epochSeconds != null) return runCatching { Instant.ofEpochSecond(epochSeconds).toString() }.getOrNull()
    val raw = (resetAt as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    if (raw != null) return raw
    val resetAfter = number("reset_after_seconds")?.toLong()?.takeIf { it >= 0 } ?: return null
    return Instant.now().plusSeconds(resetAfter).toString()
}

private fun formatWindowLength(seconds: Int): String = when {
    seconds >= 86_400 && seconds % 86_400 == 0 -> "${seconds / 86_400} 天"
    seconds >= 3_600 && seconds % 3_600 == 0 -> "${seconds / 3_600} 小时"
    seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> "$seconds 秒"
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
