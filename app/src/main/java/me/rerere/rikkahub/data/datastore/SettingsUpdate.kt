package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.model.Assistant

// Identity equality keeps Settings equality from comparing an additional serialized settings tree.
class SettingsUpdateBaseline internal constructor(internal val value: JsonObject)

internal fun Settings.withUpdateBaseline(): Settings = copy(
    updateBaseline = SettingsUpdateBaseline(JsonInstant.encodeToJsonElement(this).jsonObject),
)

internal fun Settings.withAssistantUpdate(updated: Assistant, baseline: Assistant): Settings {
    require(updated.id == baseline.id) { "Assistant editing baseline must have the same ID" }
    if (assistants.none { it.id == updated.id }) return this
    val editing = copy(assistants = assistants.map { if (it.id == baseline.id) baseline else it })
        .withUpdateBaseline()
    return editing.copy(assistants = editing.assistants.map { if (it.id == updated.id) updated else it })
}

internal fun mergeSettingsUpdate(current: Settings, requested: Settings): Settings {
    // Deserialized imports have no editing baseline and deliberately replace all settings.
    val baseline = requested.updateBaseline?.value ?: return requested
    val merged = mergeSettingValue(
        baseline,
        JsonInstant.encodeToJsonElement(requested),
        JsonInstant.encodeToJsonElement(current),
    )!!
    return JsonInstant.decodeFromJsonElement(merged)
}

private fun mergeSettingValue(
    baseline: JsonElement?,
    requested: JsonElement?,
    current: JsonElement?,
): JsonElement? {
    if (requested == baseline) return current
    if (baseline is JsonObject && requested is JsonObject && current is JsonObject) {
        val merged = current.toMutableMap()
        (baseline.keys + requested.keys).forEach { key ->
            val value = mergeSettingValue(baseline[key], requested[key], current[key])
            if (value == null) merged.remove(key) else merged[key] = value
        }
        return JsonObject(merged)
    }
    if (baseline is JsonArray && requested is JsonArray && current is JsonArray &&
        listOf(baseline, requested, current).all { values ->
            values.all { it is JsonObject && it["id"] is JsonPrimitive } &&
                values.map { (it as JsonObject)["id"] }.distinct().size == values.size
        }
    ) {
        return mergeSettingItems(baseline, requested, current)
    }
    // Lists without stable IDs are a single setting, including their explicit ordering.
    return requested
}

private fun mergeSettingItems(baseline: JsonArray, requested: JsonArray, current: JsonArray): JsonArray {
    fun JsonArray.byId(): Map<JsonElement, JsonElement> = associate { item -> (item as JsonObject).getValue("id") to item }
    val baselineItems = baseline.byId()
    val requestedItems = requested.byId()
    val currentItems = current.byId()
    val merged = currentItems.toMutableMap()
    (baselineItems.keys - requestedItems.keys).forEach(merged::remove)
    requestedItems.forEach { (id, item) ->
        // A stale editor must not resurrect a concurrently deleted assistant or provider.
        if (id !in baselineItems || id in currentItems) {
            mergeSettingValue(baselineItems[id], item, currentItems[id])?.let { merged[id] = it }
        }
    }
    val order = if (baselineItems.keys.toList() == requestedItems.keys.toList()) {
        currentItems.keys.toList()
    } else {
        (requestedItems.keys + currentItems.keys).toList()
    }
    return JsonArray(order.mapNotNull(merged::get))
}
