package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Sparse stream updates must not erase opaque protocol fields received earlier. */
internal fun mergeMetadata(previous: JsonObject?, next: JsonObject?): JsonObject? {
    if (next == null) return previous
    val merged = previous?.toMutableMap() ?: mutableMapOf()
    next.forEach { (key, value) ->
        if (value != JsonNull) {
            merged[key] = if (value is JsonObject) mergeMetadata(merged[key] as? JsonObject, value)!! else value
        }
    }
    return JsonObject(merged)
}
