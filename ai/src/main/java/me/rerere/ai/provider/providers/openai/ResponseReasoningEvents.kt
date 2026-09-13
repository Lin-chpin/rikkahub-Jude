package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

private const val REASONING_ID = "reasoning_id"
private const val REASONING_CHANNEL = "reasoning_channel"
private const val REASONING_SNAPSHOT = "reasoning_snapshot"

internal fun parseResponseReasoningEvent(jsonObject: JsonObject): MessageChunk? {
    val type = jsonObject["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
    val (channel, text, snapshot) = when (type) {
        "response.reasoning_summary_part.added" -> Triple(
            "summary",
            jsonObject["part"]?.jsonObjectOrNull?.get("text")?.jsonPrimitiveOrNull?.contentOrNull ?: "",
            false,
        )

        "response.reasoning_summary_part.done" -> Triple(
            "summary",
            jsonObject["part"]?.jsonObjectOrNull?.get("text")?.jsonPrimitiveOrNull?.contentOrNull ?: "",
            true,
        )

        "response.reasoning_summary_text.delta" -> Triple(
            "summary",
            jsonObject["delta"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
            false,
        )

        "response.reasoning_summary_text.done" -> Triple(
            "summary",
            jsonObject["text"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
            true,
        )

        "response.reasoning_text.delta" -> Triple(
            "text",
            jsonObject["delta"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
            false,
        )

        "response.reasoning_text.done" -> Triple(
            "text",
            jsonObject["text"]?.jsonPrimitiveOrNull?.contentOrNull ?: "",
            true,
        )

        else -> return null
    }

    val reasoningId = jsonObject["item_id"]?.jsonPrimitiveOrNull?.contentOrNull
    return MessageChunk(
        id = reasoningId.orEmpty(),
        model = "",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(
                            reasoning = text,
                            finishedAt = null,
                            metadata = buildJsonObject {
                                reasoningId?.let { put(REASONING_ID, it) }
                                put(REASONING_CHANNEL, channel)
                                if (snapshot) put(REASONING_SNAPSHOT, true)
                            },
                        )
                    ),
                ),
                message = null,
                finishReason = null,
            )
        ),
    )
}
