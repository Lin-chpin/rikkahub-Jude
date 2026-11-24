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
    val groups = (response["groups"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.filter { group ->
            val id = group.string("groupId").ifBlank { group.string("id") }
            "$id ${group.string("displayName")}".contains("gemini", ignoreCase = true)
        }
        ?.mapNotNull { group ->
            val buckets = (group["buckets"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.mapNotNull { it.toQuotaBucket() }
                .orEmpty()
            buckets.takeIf { it.isNotEmpty() }?.let {
                val id = group.string("groupId").ifBlank { group.string("id") }
                GeminiOAuthQuotaGroup(
                    id = id.ifBlank { group.string("displayName") },
                    displayName = group.string("displayName").ifBlank { "Gemini" },
                    buckets = it,
                )
            }
        }
        .orEmpty()
    if (groups.isNotEmpty()) return groups

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
        quota.toQuotaBucket(id = modelId, displayName = displayName)
    }
    return modelBuckets.takeIf { it.isNotEmpty() }?.let {
        listOf(GeminiOAuthQuotaGroup("gemini-models", "Gemini Models", it))
    }.orEmpty()
}

private fun JsonObject.toQuotaBucket(
    id: String = string("bucketId").ifBlank { string("id").ifBlank { string("displayName") } },
    displayName: String = string("displayName").ifBlank { id },
): GeminiOAuthQuotaBucket? {
    val remaining = this["remaining"] as? JsonObject
    val fraction = (this["remainingFraction"] as? JsonPrimitive)?.doubleOrNull
        ?: (remaining?.get("remainingFraction") as? JsonPrimitive)?.doubleOrNull
        ?: (this["remaining"] as? JsonPrimitive)?.doubleOrNull
    val normalizedFraction = fraction?.takeIf { it in 0.0..1.0 }
    val resetTime = string("resetTime").ifBlank {
        remaining?.string("resetTime").orEmpty()
    }.takeIf(String::isNotBlank)
    if (id.isBlank() || (normalizedFraction == null && resetTime == null)) return null
    return GeminiOAuthQuotaBucket(
        id = id,
        displayName = displayName.ifBlank { id },
        window = string("window").takeIf(String::isNotBlank),
        remainingFraction = normalizedFraction,
        resetTime = resetTime,
    )
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.boolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull == true
