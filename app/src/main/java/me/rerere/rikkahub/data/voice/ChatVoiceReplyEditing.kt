package me.rerere.rikkahub.data.voice

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ChatVoiceReplySegment
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

fun UIMessage.withEditedChatVoiceReplySegments(segments: List<ChatVoiceReplySegment>): UIMessage {
    val original = chatVoiceReply() ?: return this
    val updated = segments.filter { it.text.isNotBlank() }.map { segment ->
        when {
            segment.type == ChatVoiceReplySegmentType.TEXT -> segment.copy(audioSegments = emptyList(), translation = null)
            original.segments.any { it.type == segment.type && it.text == segment.text && it.audioSegments == segment.audioSegments } -> segment
            // Changed spoken words cannot retain an audio file saying the old words.
            else -> segment.copy(type = ChatVoiceReplySegmentType.TEXT, audioSegments = emptyList(), translation = null)
        }
    }
    require(updated.isNotEmpty()) { "Use delete reply to remove every segment" }
    val arguments = buildJsonObject {
        put("segments", buildJsonArray {
            updated.forEach { segment ->
                add(buildJsonObject {
                    put("type", segment.type.name.lowercase())
                    put("text", segment.text)
                })
            }
        })
    }.toString()
    val edited = withChatVoiceReply(ParsedChatVoiceReply(updated))
    return edited.copy(
        translation = null,
        parts = edited.parts.map { part ->
            if (part is UIMessagePart.Tool && part.toolName == CHAT_VOICE_REPLY_TOOL_NAME) {
                part.copy(input = arguments)
            } else part
        },
    )
}

/** Generic text editors may change surrounding text, but must preserve the recorded voice anchors. */
internal fun UIMessage.withEditedChatVoiceReplyText(parts: List<UIMessagePart>): UIMessage {
    val reply = chatVoiceReply() ?: return copy(parts = parts)
    val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val segments = mutableListOf<ChatVoiceReplySegment>()
    var cursor = 0
    reply.segments.filter { it.type == ChatVoiceReplySegmentType.VOICE }.forEach { voice ->
        val spokenText = voice.text.withoutFlexibleVoiceCallAudioTags()
        val start = text.indexOf(spokenText, cursor)
        require(start >= cursor) { "Edit voice replies with the segment editor to preserve recorded audio" }
        text.substring(cursor, start).trim().takeIf { it.isNotEmpty() }?.let {
            segments += ChatVoiceReplySegment(ChatVoiceReplySegmentType.TEXT, it)
        }
        segments += voice
        cursor = start + spokenText.length
    }
    text.substring(cursor).trim().takeIf { it.isNotEmpty() }?.let {
        segments += ChatVoiceReplySegment(ChatVoiceReplySegmentType.TEXT, it)
    }
    return copy(parts = parts).withEditedChatVoiceReplySegments(segments)
}
