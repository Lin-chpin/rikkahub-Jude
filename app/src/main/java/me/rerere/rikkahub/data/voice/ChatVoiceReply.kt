package me.rerere.rikkahub.data.voice

import me.rerere.ai.ui.ChatVoiceReplySegment
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

const val CHAT_VOICE_REPLY_TOOL_NAME = "text_to_speech"

const val CHAT_VOICE_REPLY_TOOL_RESULT_PROMPT = """
PROTOCOL LOCK: You have already called the text_to_speech tool. Your next assistant message MUST be the complete final reply in the segment format below. This is a hard requirement, not a suggestion. A plain prose reply without at least one 【语音条】 segment is invalid.

Now write the complete final reply using the following segment format.

Start every segment with exactly one marker:
【语音条】content to synthesize as a voice message
【文本】content to display as ordinary chat text

You may use either marker multiple times and in any order. At least one segment must be 【语音条】.
If the whole reply should be a voice message, output only 【语音条】 followed by the reply.
Keep each voice segment at sentence level; the client will split it into separate audio chunks before synthesis.
Voice content must be plain speakable text. Do not use emoji, emoticons, kaomoji, stickers, ASCII faces, or decorative symbol combinations.
Do not answer with an explanation of this protocol. Do not omit the 【语音条】 marker, even when the reply is short.
Do not use a code block, do not explain the markers, and do not repeat voice content in a text segment.
"""

data class ParsedChatVoiceReply(
    val segments: List<ChatVoiceReplySegment>,
) {
    val plainText: String = segments.joinToString("\n\n") { it.text }
}

private val chatVoiceReplyMarker = Regex("【(语音条|文本)】")

fun parseChatVoiceReply(text: String): ParsedChatVoiceReply? {
    val matches = chatVoiceReplyMarker.findAll(text).toList()
    if (matches.none { it.groupValues[1] == "语音条" }) return null

    val segments = buildList {
        val leadingText = text.substring(0, matches.first().range.first).trim()
        if (leadingText.isNotBlank()) {
            add(ChatVoiceReplySegment(ChatVoiceReplySegmentType.TEXT, leadingText))
        }
        matches.forEachIndexed { index, match ->
            val contentStart = match.range.last + 1
            val contentEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val content = text.substring(contentStart, contentEnd).trim()
            if (content.isNotBlank()) {
                add(
                    ChatVoiceReplySegment(
                        type = if (match.groupValues[1] == "语音条") {
                            ChatVoiceReplySegmentType.VOICE
                        } else {
                            ChatVoiceReplySegmentType.TEXT
                        },
                        text = content,
                    )
                )
            }
        }
    }
    if (segments.none { it.type == ChatVoiceReplySegmentType.VOICE }) return null
    return ParsedChatVoiceReply(segments)
}

/**
 * Keeps a successful voice-tool turn usable when the model forgets the segment
 * markers in its final continuation. The tool call is the explicit voice intent;
 * the plain assistant continuation becomes one voice segment as a last resort.
 */
internal fun parseChatVoiceReplyAsVoiceFallback(text: String): ParsedChatVoiceReply? {
    val plainText = text
        .replace(chatVoiceReplyMarker, "")
        .trim()
        .takeIf { it.isNotBlank() }
        ?: return null
    return ParsedChatVoiceReply(
        segments = listOf(
            ChatVoiceReplySegment(
                type = ChatVoiceReplySegmentType.VOICE,
                text = plainText,
            )
        )
    )
}

fun UIMessage.chatVoiceReply(): UIMessageAnnotation.ChatVoiceReply? =
    annotations.filterIsInstance<UIMessageAnnotation.ChatVoiceReply>().firstOrNull()

fun UIMessage.chatVoiceReplyDraft(): ParsedChatVoiceReply? =
    if (chatVoiceReply() == null) parseChatVoiceReply(chatVoiceReplySourceText()) else null

internal fun UIMessage.chatVoiceReplySourceText(): String = parts
    .filterIsInstance<UIMessagePart.Text>()
    .lastOrNull { it.text.contains("【语音条】") }
    ?.text
    ?: toText()

fun UIMessage.hasChatVoiceReplyTool(): Boolean = parts.any { part ->
    part is UIMessagePart.Tool && part.toolName == CHAT_VOICE_REPLY_TOOL_NAME
}

fun UIMessage.hasChatVoiceReplyToolError(): Boolean = parts
    .filterIsInstance<UIMessagePart.Tool>()
    .any { it.toolName == CHAT_VOICE_REPLY_TOOL_NAME && it.chatVoiceReplyError() != null }

fun UIMessage.hasPendingChatVoiceReply(): Boolean {
    if (chatVoiceReply() != null) return false
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
