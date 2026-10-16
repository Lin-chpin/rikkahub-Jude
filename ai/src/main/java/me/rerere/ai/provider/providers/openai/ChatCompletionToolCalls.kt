package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeMetadata
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

private const val CALL_METADATA = "openai_chat_tool_call"
private val signatureFields = setOf("thought_signature", "thoughtSignature", "extra_content")

internal fun JsonObject.chatToolMetadata(): JsonObject? {
    val fields = filterKeys { it in signatureFields }.toMutableMap()
    get("function")?.jsonObjectOrNull?.filterKeys { it in signatureFields }
        ?.takeIf { it.isNotEmpty() }?.let { fields["function"] = JsonObject(it) }
    return fields.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put(CALL_METADATA, JsonObject(it)) } }
}

internal fun UIMessagePart.Tool.toChatToolCall(): JsonObject = buildJsonObject {
    val saved = metadata?.get(CALL_METADATA)?.jsonObjectOrNull
    saved?.filterKeys { it in signatureFields }?.forEach { (key, value) -> put(key, value) }
    put("id", toolCallId)
    put("type", "function")
    put("function", buildJsonObject {
        saved?.get("function")?.jsonObjectOrNull?.filterKeys { it in signatureFields }
            ?.forEach { (key, value) -> put(key, value) }
        put("name", toolName)
        put("arguments", input)
    })
}

/** Delta IDs are usually absent after the first event; index, not arrival order, owns each call. */
internal class ChatCompletionToolCalls {
    private data class Call(
        var id: String = "",
        var name: String = "",
        var arguments: String = "",
        var metadata: JsonObject? = null,
    )

    private val calls = sortedMapOf<Int, Call>()
    private var finished = false

    fun accept(items: JsonArray, snapshot: Boolean = false) {
        items.forEachIndexed { position, element ->
            val item = element.jsonObjectOrNull ?: error("Invalid chat tool call")
            val type = item["type"]?.jsonPrimitiveOrNull?.contentOrNull
            check(type.isNullOrBlank() || type == "function") { "Unsupported chat tool call type" }
            val index = item["index"]?.jsonPrimitiveOrNull?.intOrNull
                ?: if (snapshot) position else error("Chat tool call index not found")
            check(index >= 0) { "Invalid chat tool call index" }
            val call = calls.getOrPut(index) { Call() }
            item["id"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                check(call.id.isEmpty() || call.id == it) { "Chat tool call ID changed within one index" }
                call.id = it
            }
            val function = item["function"]?.jsonObjectOrNull
            function?.get("name")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                call.name = if (snapshot) it else call.name + it
            }
            function?.get("arguments")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                call.arguments = if (snapshot) it else call.arguments + it
            }
            call.metadata = mergeMetadata(call.metadata, item.chatToolMetadata())
        }
    }

    fun finish(): List<UIMessagePart.Tool> {
        if (finished) return emptyList()
        val tools = calls.values.map { call ->
            check(call.id.isNotBlank() && call.name.isNotBlank()) { "Chat tool call ID or name not found" }
            check(runCatching { json.parseToJsonElement(call.arguments) is JsonObject }.getOrDefault(false)) {
                "Chat tool call arguments missing or invalid"
            }
            UIMessagePart.Tool(call.id, call.name, call.arguments, metadata = call.metadata)
        }
        check(tools.map { it.toolCallId }.distinct().size == tools.size) { "Duplicate chat tool call IDs" }
        finished = true
        return tools
    }
}
