package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

/** One instance per stream; snapshots and argument deltas must never both be appended to a tool. */
internal class ResponseToolCallEvents {
    private class Call {
        var callId: String? = null
        var name: String? = null
        var initialArguments = ""
        var finalArguments: String? = null
        var metadata: JsonObject? = null
        val deltas = StringBuilder()
    }

    private val calls = linkedMapOf<String, Call>()
    private var finished = false

    fun accept(event: JsonObject) {
        when (event.string("type")) {
            "response.output_item.added" -> event["item"]?.jsonObjectOrNull?.let {
                acceptItem(it, final = false)
            }
            "response.output_item.done" -> event["item"]?.jsonObjectOrNull?.let {
                acceptItem(it, final = true)
            }
            "response.function_call_arguments.delta" -> {
                call(event.string("item_id")).deltas.append(
                    event.string("delta") ?: error("Responses function call delta not found")
                )
            }
            "response.function_call_arguments.done" -> {
                val call = call(event.string("item_id"))
                event.string("arguments")?.takeIf { it.isNotBlank() }?.let {
                    call.finalArguments = it
                }
            }
            "response.completed" -> {
                event["response"]?.jsonObjectOrNull?.get("output")?.jsonArray?.forEach {
                    it.jsonObjectOrNull?.let { item -> acceptItem(item, final = true) }
                }
            }
        }
    }

    private fun call(itemId: String?): Call {
        check(!itemId.isNullOrBlank()) { "Responses function call item_id not found" }
        return calls.getOrPut(itemId) { Call() }
    }

    private fun acceptItem(item: JsonObject, final: Boolean) {
        if (item.string("type") != "function_call") return
        val call = call(item.string("id"))
        call.metadata = item.responseToolCallMetadata(call.metadata)
        item.string("call_id")?.let { call.callId = it }
        item.string("name")?.let { call.name = it }
        item.string("arguments")?.let {
            if (final) {
                call.finalArguments = it.takeIf { arguments -> arguments.isNotBlank() }
                    ?: call.finalArguments
            } else {
                call.initialArguments = it
            }
        }
    }

    fun finish(): MessageChunk? {
        if (finished || calls.isEmpty()) return null
        val tools = calls.values.map { call ->
            val callId = checkNotNull(call.callId?.takeIf { it.isNotBlank() }) {
                "Responses function call call_id not found"
            }
            val name = checkNotNull(call.name?.takeIf { it.isNotBlank() }) {
                "Responses function call name not found"
            }
            // Some compatible servers omit arguments on .done; use the received fragments instead.
            val arguments = call.finalArguments ?: (call.initialArguments + call.deltas)
            check(runCatching { Json.parseToJsonElement(arguments) is JsonObject }.getOrDefault(false)) {
                "Responses function call arguments missing or invalid"
            }
            UIMessagePart.Tool(toolCallId = callId, toolName = name, input = arguments, metadata = call.metadata)
        }
        finished = true
        return MessageChunk(
            id = "",
            model = "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = tools),
                    message = null,
                    finishReason = null,
                )
            ),
        )
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitiveOrNull?.contentOrNull
