package me.rerere.rikkahub.data.ai.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

internal data class GeminiOAuthQuotaGroup(
    val id: String,
    val displayName: String,
    val buckets: List<GeminiOAuthQuotaBucket>,
)

internal data class GeminiOAuthQuotaBucket(
    val id: String,
    val displayName: String,
    val window: String?,
    val remainingFraction: Double?,
    val resetTime: String?,
)

internal fun parseGeminiOAuthQuota(
    summary: JsonObject,
    modelCatalog: JsonObject? = null,
): List<GeminiOAuthQuotaGroup> {
    val response = summary["response"] as? JsonObject ?: summary
    val rawGroups = (response["groups"] as? JsonArray) ?: (response["quotaGroups"] as? JsonArray)
    val groups = rawGroups
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { group ->
            val id = group.string("groupId").ifBlank { group.string("id") }
            val displayName = group.string("displayName")
            val groupLooksGemini = "$id $displayName".contains("gemini", ignoreCase = true)
            val rawBuckets = (group["buckets"] as? JsonArray) ?: (group["quotaBuckets"] as? JsonArray)
            val buckets = rawBuckets
                ?.mapNotNull { it as? JsonObject }
                ?.flatMap { it.toQuotaBuckets() }
                .orEmpty()
                .filter { groupLooksGemini || it.looksGemini() }
            buckets.takeIf { it.isNotEmpty() }?.let {
                GeminiOAuthQuotaGroup(
                    id = id.ifBlank { displayName.ifBlank { "gemini" } },
                    displayName = displayName.ifBlank { "Gemini" },
                    buckets = it,
                )
            }
        }
        .orEmpty()
    if (groups.isNotEmpty()) return groups

    val topLevelBuckets = ((response["buckets"] as? JsonArray) ?: (response["quotaBuckets"] as? JsonArray))
        ?.mapNotNull { it as? JsonObject }
        ?.flatMap { it.toQuotaBuckets() }
        ?.filter { it.looksGemini() }
        .orEmpty()
    if (topLevelBuckets.isNotEmpty()) {
        return listOf(GeminiOAuthQuotaGroup("gemini", "Gemini", topLevelBuckets))
    }

    val models = modelCatalog?.get("models") as? JsonObject ?: return emptyList()
    val modelBuckets = models.mapNotNull { (modelId, value) ->
        val model = value as? JsonObject ?: return@mapNotNull null
        val displayName = model.string("displayName").ifBlank { modelId }
        if (!"$modelId $displayName".contains("gemini", ignoreCase = true)) return@mapNotNull null
        if (model.boolean("isInternal") || model.boolean("disabled") ||
            modelId.contains("-sonar", ignoreCase = true) ||
            modelId.contains("-disabled", ignoreCase = true)
        ) return@mapNotNull null
        val quota = model["quotaInfo"] as? JsonObject ?: return@mapNotNull null
        quota.toSingleQuotaBucket(
            id = modelId,
            displayName = displayName,
            inferWindowFromId = false,
        )
    }
    return modelBuckets.takeIf { it.isNotEmpty() }?.let {
        listOf(GeminiOAuthQuotaGroup("gemini-models", "Gemini Models", it))
    }.orEmpty()
}

private fun GeminiOAuthQuotaBucket.looksGemini(): Boolean =
    "$id $displayName".contains("gemini", ignoreCase = true)

private fun JsonObject.toQuotaBuckets(): List<GeminiOAuthQuotaBucket> {
    val baseId = string("bucketId").ifBlank { string("id").ifBlank { string("displayName") } }
    val baseDisplayName = string("displayName").ifBlank { baseId }
    if (baseId.isBlank()) return emptyList()

    val windowEntries = (this["windows"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { entry ->
            val entryId = entry.string("bucketId").ifBlank { entry.string("id").ifBlank { baseId } }
            val entryName = entry.string("displayName").ifBlank { baseDisplayName }
            entry.toSingleQuotaBucket(
                id = entryId,
                displayName = entryName,
                inferWindowFromId = true,
            )
        }
        .orEmpty()
    if (windowEntries.isNotEmpty()) return windowEntries

    val namedWindows = mutableListOf<GeminiOAuthQuotaBucket>()
    val fiveHourObj = (this["fiveHour"] as? JsonObject) ?: (this["five_hour"] as? JsonObject)
    val weeklyObj = (this["weekly"] as? JsonObject)
        ?: (this["sevenDay"] as? JsonObject)
        ?: (this["seven_day"] as? JsonObject)
    if (fiveHourObj != null || weeklyObj != null) {
        if (fiveHourObj != null) {
            fiveHourObj.toSingleQuotaBucket(
                id = "$baseId-5h",
                displayName = baseDisplayName,
                fallbackWindow = "5h",
                inferWindowFromId = false,
            )?.let(namedWindows::add)
        } else {
            toSingleQuotaBucket(
                id = baseId,
                displayName = baseDisplayName,
                fallbackWindow = null,
                inferWindowFromId = true,
            )?.takeUnless { it.window == "weekly" }?.let(namedWindows::add)
        }
        if (weeklyObj != null) {
            weeklyObj.toSingleQuotaBucket(
                id = "$baseId-weekly",
                displayName = baseDisplayName,
                fallbackWindow = "weekly",
                inferWindowFromId = false,
            )?.let(namedWindows::add)
        }
        if (namedWindows.isNotEmpty()) return namedWindows
    }

    return listOfNotNull(
        toSingleQuotaBucket(
            id = baseId,
            displayName = baseDisplayName,
            inferWindowFromId = true,
        )
    )
}

private fun JsonObject.toSingleQuotaBucket(
    id: String,
    displayName: String,
    fallbackWindow: String? = null,
    inferWindowFromId: Boolean = true,
): GeminiOAuthQuotaBucket? {
    val remaining = this["remaining"] as? JsonObject
    val quotaInfo = this["quotaInfo"] as? JsonObject
    val fraction = double("remainingFraction")
        ?: remaining?.double("remainingFraction")
        ?: remaining?.double("fraction")
        ?: quotaInfo?.double("remainingFraction")
        ?: double("remaining")
    val normalizedFraction = fraction?.takeIf { it in 0.0..1.0 }
    val resetTime = string("resetTime")
        .ifBlank { remaining?.string("resetTime").orEmpty() }
        .ifBlank { quotaInfo?.string("resetTime").orEmpty() }
        .takeIf(String::isNotBlank)
    if (id.isBlank() || (normalizedFraction == null && resetTime == null)) return null
    val window = extractWindow(fallbackId = id, inferFromId = inferWindowFromId) ?: fallbackWindow
    return GeminiOAuthQuotaBucket(
        id = id,
        displayName = displayName.ifBlank { id },
        window = window,
        remainingFraction = normalizedFraction,
        resetTime = resetTime,
    )
}

private fun JsonObject.extractWindow(fallbackId: String, inferFromId: Boolean): String? {
    val raw = string("window")
        .ifBlank { string("windowType") }
        .ifBlank { string("duration") }
        .ifBlank { string("period") }
        .ifBlank { string("interval") }
        .ifBlank {
            val windowObj = this["window"] as? JsonObject
            windowObj?.string("label")?.ifBlank { windowObj.string("duration") }.orEmpty()
        }
    val normalized = normalizeWindowLabel(raw)
    if (normalized != null) return normalized
    if (!inferFromId) return null
    return inferWindowFromBucketId(fallbackId)
}

private fun normalizeWindowLabel(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return when (trimmed.lowercase()) {
        "5h", "5hr", "5hrs", "5 hour", "5 hours", "5-hour", "5-hours", "5_hour", "5_hours",
        "five_hour", "five-hour", "five_hours", "five hours", "quota_window_5h", "18000s" -> "5h"
        "weekly", "week", "1w", "1 week", "quota_window_weekly", "604800s" -> "weekly"
        "7d", "7days", "7 days", "seven_day", "seven_days", "seven-day", "seven-days",
        "seven day", "seven days", "quota_window_7d" -> "7d"
        "daily", "24h", "1d", "1 day", "quota_window_daily", "86400s" -> "24h"
        else -> trimmed
    }
}

private val BUCKET_WINDOW_REGEX =
    Regex(
        """(?:^|[-_.\s])(5h(?:rs?)?|5[-_]?hours?|five[-_]?hours?|weekly|week|seven[-_]?days?|7days?|7d|1w|24h|daily|1d)(?:$|[-_.\s])""",
        RegexOption.IGNORE_CASE,
    )

private fun inferWindowFromBucketId(id: String): String? {
    val match = BUCKET_WINDOW_REGEX.find(id) ?: return null
    return normalizeWindowLabel(match.groupValues[1])
}

private fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.boolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull == true
