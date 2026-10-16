package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.mergeMetadata
import me.rerere.common.http.jsonObjectOrNull

private const val RESPONSE_TOOL_CALL_METADATA = "openai_responses_function_call"
private val replayFields = setOf("id", "thought_signature", "thoughtSignature", "extra_content")

/** Keep opaque signatures on their original call, including extensions from compatible servers. */
internal fun JsonObject.responseToolCallMetadata(previous: JsonObject? = null): JsonObject? {
    val saved = previous?.get(RESPONSE_TOOL_CALL_METADATA)?.jsonObjectOrNull
        ?: JsonObject(emptyMap())
    val received = JsonObject(filterKeys { it in replayFields })
    val merged = mergeMetadata(saved, received)!!
    if (merged.isEmpty()) return previous
    return buildJsonObject {
        previous?.forEach { (key, value) -> put(key, value) }
        put(RESPONSE_TOOL_CALL_METADATA, merged)
    }
}

internal fun UIMessagePart.Tool.toResponseFunctionCall(): JsonObject = buildJsonObject {
    metadata?.get(RESPONSE_TOOL_CALL_METADATA)?.jsonObjectOrNull
        ?.filterKeys { it in replayFields }
        ?.forEach { (key, value) -> put(key, value) }
    put("type", "function_call")
    put("call_id", toolCallId)
    put("name", toolName)
    put("arguments", input)
}
