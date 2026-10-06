package me.rerere.rikkahub.data.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ChatVoiceReplySegment
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

const val CHAT_VOICE_REPLY_TOOL_NAME = "text_to_speech"

const val CHAT_VOICE_REPLY_TOOL_RESULT_PROMPT =
    "Voice segments accepted for client-side synthesis. Audio generation and delivery are not confirmed. Do not repeat the segments or claim they were sent."

data class ParsedChatVoiceReply(
    val segments: List<ChatVoiceReplySegment>,
) {
    val plainText: String = segments.joinToString("\n\n") { it.displayText() }
}

private fun ChatVoiceReplySegment.displayText(): String =
    if (type == ChatVoiceReplySegmentType.VOICE) text.withoutFlexibleVoiceCallAudioTags() else text

internal fun parseChatVoiceReplyArguments(arguments: JsonElement): ParsedChatVoiceReply? {
    val items = ((arguments as? JsonObject)?.get("segments") as? JsonArray) ?: return null
    if (items.isEmpty()) return null
    val segments = items.map { item ->
        val values = item as? JsonObject ?: return null
        val typeValue = values["type"] as? JsonPrimitive ?: return null
        if (!typeValue.isString) return null
        val type = when (typeValue.contentOrNull) {
            "text" -> ChatVoiceReplySegmentType.TEXT
            "voice" -> ChatVoiceReplySegmentType.VOICE
            else -> return null
        }
        val textValue = values["text"] as? JsonPrimitive ?: return null
        if (!textValue.isString) return null
        val text = textValue.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) return null
        ChatVoiceReplySegment(type, text)
    }
    return segments.takeIf { list -> list.any { it.type == ChatVoiceReplySegmentType.VOICE } }
        ?.let(::ParsedChatVoiceReply)
}

fun UIMessage.chatVoiceReply(): UIMessageAnnotation.ChatVoiceReply? =
    annotations.filterIsInstance<UIMessageAnnotation.ChatVoiceReply>().firstOrNull()

fun UIMessage.chatVoiceReplyDraft(): ParsedChatVoiceReply? =
    if (chatVoiceReply() != null) null else parts
        .filterIsInstance<UIMessagePart.Tool>()
        .filter { it.toolName == CHAT_VOICE_REPLY_TOOL_NAME }
        .flatMap { parseChatVoiceReplyArguments(it.inputAsJson())?.segments.orEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.let(::ParsedChatVoiceReply)

fun UIMessage.hasChatVoiceReplyTool(): Boolean = parts.any { part ->
    part is UIMessagePart.Tool && part.toolName == CHAT_VOICE_REPLY_TOOL_NAME
}

fun UIMessage.hasChatVoiceReplyToolError(): Boolean = parts
    .filterIsInstance<UIMessagePart.Tool>()
    .any { it.toolName == CHAT_VOICE_REPLY_TOOL_NAME && it.chatVoiceReplyError() != null }

fun UIMessage.hasPendingChatVoiceReply(): Boolean {
    if (chatVoiceReply() != null) return false
    if (hasChatVoiceReplyToolError()) return false
    val hasExecutedVoiceTool = parts.any { part ->
        part is UIMessagePart.Tool &&
            part.toolName == CHAT_VOICE_REPLY_TOOL_NAME &&
            part.isExecuted
    }
    if (!hasExecutedVoiceTool) return false
    return chatVoiceReplyDraft() != null
}

fun UIMessage.withChatVoiceReply(reply: ParsedChatVoiceReply): UIMessage =
    replaceChatVoiceReplyText(reply.plainText).copy(
        annotations = annotations
            .filterNot { it is UIMessageAnnotation.ChatVoiceReply }
            .plus(UIMessageAnnotation.ChatVoiceReply(reply.segments)),
    )

fun UIMessage.withChatVoiceReplyPlainText(reply: ParsedChatVoiceReply): UIMessage =
    replaceChatVoiceReplyText(reply.plainText).copy(
        annotations = annotations.filterNot { it is UIMessageAnnotation.ChatVoiceReply },
    )

private fun UIMessage.replaceChatVoiceReplyText(text: String): UIMessage {
    var textReplaced = false
    val updatedParts = parts.mapNotNull { part ->
        if (part !is UIMessagePart.Text) return@mapNotNull part
        if (textReplaced) return@mapNotNull null
        textReplaced = true
        part.copy(text = text)
    }.let { current ->
        if (textReplaced) current else current + UIMessagePart.Text(text)
    }
    return copy(parts = updatedParts)
}

fun UIMessage.withChatVoiceReplyToolError(error: ChatVoiceReplyError): UIMessage = copy(
    parts = parts.map { part ->
        if (part is UIMessagePart.Tool && part.toolName == CHAT_VOICE_REPLY_TOOL_NAME) {
            part.copy(output = listOf(UIMessagePart.Text(encodeChatVoiceReplyError(error))))
        } else {
            part
        }
    }
)

fun UIMessage.updateChatVoiceReplySegment(
    segmentIndex: Int,
    transform: (ChatVoiceReplySegment) -> ChatVoiceReplySegment,
): UIMessage = copy(
    annotations = annotations.map { annotation ->
        if (annotation is UIMessageAnnotation.ChatVoiceReply) {
            annotation.copy(
                segments = annotation.segments.mapIndexed { index, segment ->
                    if (index == segmentIndex) transform(segment) else segment
                }
            )
        } else {
            annotation
        }
    }
)

fun UIMessage.expandChatVoiceReplySegment(
    segmentIndex: Int,
    replacements: List<ChatVoiceReplySegment>,
): UIMessage = copy(
    annotations = annotations.map { annotation ->
        if (annotation is UIMessageAnnotation.ChatVoiceReply) {
            annotation.copy(
                segments = annotation.segments.flatMapIndexed { index, segment ->
                    if (index == segmentIndex) replacements else listOf(segment)
                }
            )
        } else {
            annotation
        }
    }
)
