package me.rerere.rikkahub.data.voice

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVoiceReplyTest {
    @Test
    fun parsesMixedVoiceAndTextSegmentsInOrder() {
        val parsed = requireNotNull(
            parseChatVoiceReply("【语音条】我好想你【文本】你刚刚没听见吧")
        )

        assertEquals(
            listOf(ChatVoiceReplySegmentType.VOICE, ChatVoiceReplySegmentType.TEXT),
            parsed.segments.map { it.type },
        )
        assertEquals(listOf("我好想你", "你刚刚没听见吧"), parsed.segments.map { it.text })
    }

    @Test
    fun supportsAWholeReplyAndMultipleVoiceSegments() {
        val wholeVoice = requireNotNull(parseChatVoiceReply("【语音条】整段都是语音"))
        assertEquals(ChatVoiceReplySegmentType.VOICE, wholeVoice.segments.single().type)

        val multiple = requireNotNull(
            parseChatVoiceReply("【语音条】第一条【语音条】第二条【文本】最后一句")
        )
        assertEquals(3, multiple.segments.size)
    }

    @Test
    fun ignoresUnstructuredOrdinaryReplies() {
        assertNull(parseChatVoiceReply("这是一条普通文字回复。"))
        assertNull(parseChatVoiceReply("【文本】只有普通文字"))
    }

    @Test
    fun materializationRemovesMarkersFromModelContext() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("【语音条】我好想你【文本】你刚刚没听见吧")),
        )
        val parsed = requireNotNull(parseChatVoiceReply(message.toText()))
        val materialized = message.withChatVoiceReply(parsed)

        assertEquals("我好想你\n\n你刚刚没听见吧", materialized.toText())
        assertEquals(2, materialized.chatVoiceReply()?.segments?.size)
    }

    @Test
    fun findsStructuredReplyMergedIntoTheExecutedToolMessage() {
        val existingMessage = UIMessage.user("请发一条语音")
        val toolReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-1",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = "{}",
                    output = listOf(UIMessagePart.Text(CHAT_VOICE_REPLY_TOOL_RESULT_PROMPT)),
                ),
                UIMessagePart.Text("【语音条】我好想你【文本】你刚刚没听见吧"),
            ),
        )

        val target = findChatVoiceReplyMaterializationTarget(
            messages = listOf(existingMessage, toolReply),
            generationBaseMessageIds = setOf(existingMessage.id),
        )

        assertEquals(toolReply.id, target?.replyMessage?.id)
        assertEquals(
            listOf(ChatVoiceReplySegmentType.VOICE, ChatVoiceReplySegmentType.TEXT),
            target?.parsedReply?.segments?.map { it.type },
        )
    }

    @Test
    fun hidesStructuredReplyUntilVoiceReplyIsMaterialized() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-2",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = "{}",
                    output = listOf(UIMessagePart.Text("done")),
                ),
                UIMessagePart.Text("【语音条】先隐藏这段结构化文本"),
            ),
        )

        assertTrue(message.hasPendingChatVoiceReply())
        val parsed = requireNotNull(parseChatVoiceReply(message.toText()))
        assertFalse(message.withChatVoiceReply(parsed).hasPendingChatVoiceReply())
    }
}
