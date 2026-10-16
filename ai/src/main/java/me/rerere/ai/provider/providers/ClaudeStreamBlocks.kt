package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

/** Claude streams signature fragments and tool arguments against a content-block index. */
internal class ClaudeStreamBlocks {
    private val signatures = mutableMapOf<Int, String>()
    private val toolIds = mutableMapOf<Int, String>()

    fun accept(event: JsonObject): JsonArray = buildJsonArray {
        val index = event["index"]?.jsonPrimitiveOrNull?.intOrNull
        event["content_block"]?.jsonObjectOrNull?.let { block ->
            when (block["type"]?.jsonPrimitiveOrNull?.contentOrNull) {
                "tool_use" -> toolIds[checkNotNull(index)] =
                    checkNotNull(block["id"]?.jsonPrimitiveOrNull?.contentOrNull)
                "thinking" -> signatures[checkNotNull(index)] =
                    block["signature"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
            }
            add(withBlockId(block, index))
        }
        event["delta"]?.jsonObjectOrNull?.let { delta ->
            add(withBlockId(buildJsonObject {
                delta.forEach { (key, value) -> put(key, value) }
                when (delta["type"]?.jsonPrimitiveOrNull?.contentOrNull) {
                    "signature_delta" -> {
                        val blockIndex = checkNotNull(index)
                        val signature = signatures[blockIndex].orEmpty() +
                            delta["signature"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                        signatures[blockIndex] = signature
                        put("signature", signature)
                    }
                    "input_json_delta" -> put("id", checkNotNull(toolIds[index]) { "Claude tool block not found" })
                }
            }, index))
        }
    }

    private fun withBlockId(block: JsonObject, index: Int?): JsonObject {
        if (block["type"]?.jsonPrimitiveOrNull?.contentOrNull !in
            setOf("thinking", "thinking_delta", "signature_delta", "redacted_thinking")) return block
        return buildJsonObject {
            block.forEach { (key, value) -> put(key, value) }
            put("reasoning_id", "claude_${checkNotNull(index)}")
        }
    }
}
