package me.rerere.rikkahub.data.voice

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.provider.TTSProviderSetting
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
    fun prefersTheFinalStructuredReplyAfterTheToolMessage() {
        val existingMessage = UIMessage.user("请发一条语音")
        val firstToolReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-first",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = "{}",
                    output = listOf(UIMessagePart.Text("done")),
                ),
                UIMessagePart.Text("【语音条】第一次流式输出"),
            ),
        )
        val finalReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("【语音条】最终输出【文本】补充说明")),
        )

        val target = findChatVoiceReplyMaterializationTarget(
            messages = listOf(existingMessage, firstToolReply, finalReply),
            generationBaseMessageIds = setOf(existingMessage.id),
        )

        assertEquals(finalReply.id, target?.replyMessage?.id)
        assertEquals(listOf("最终输出", "补充说明"), target?.parsedReply?.segments?.map { it.text })
    }

    @Test
    fun fallsBackToPlainAssistantReplyWhenVoiceMarkersAreMissing() {
        val existingMessage = UIMessage.user("请发一条语音")
        val toolReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-fallback",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = "{}",
                    output = listOf(UIMessagePart.Text(CHAT_VOICE_REPLY_TOOL_RESULT_PROMPT)),
                )
            ),
        )
        val plainReply = UIMessage.assistant("这是模型忘记加语音标记后的普通回复")

        val target = findChatVoiceReplyMaterializationTarget(
            messages = listOf(existingMessage, toolReply, plainReply),
            generationBaseMessageIds = setOf(existingMessage.id),
        )

        assertEquals(plainReply.id, target?.replyMessage?.id)
        assertTrue(target?.usedProtocolFallback == true)
        assertEquals(
            listOf(ChatVoiceReplySegmentType.VOICE),
            target?.parsedReply?.segments?.map { it.type },
        )
        assertEquals("这是模型忘记加语音标记后的普通回复", target?.parsedReply?.plainText)
    }

    @Test
    fun ignoresAnExecutedVoiceToolAlreadyPresentBeforeGeneration() {
        val existingMessage = UIMessage.user("请继续聊天")
        val oldToolReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-old",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = "{}",
                    output = listOf(UIMessagePart.Text("done")),
                ),
            ),
        )
        val ordinaryReply = UIMessage.assistant("这是本轮普通文字回复")

        val target = findChatVoiceReplyMaterializationTarget(
            messages = listOf(existingMessage, oldToolReply, ordinaryReply),
            generationBaseMessageIds = setOf(existingMessage.id, oldToolReply.id),
        )

        assertNull(target)
    }

    @Test
    fun usesTheLatestStructuredTextPartWhenStreamingRepeatedTheProtocol() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("【语音条】旧的流式内容"),
                UIMessagePart.Tool(
                    toolCallId = "voice-repeat",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = "{}",
                    output = listOf(UIMessagePart.Text("done")),
                ),
                UIMessagePart.Text("【语音条】最终内容【文本】补充内容"),
            ),
        )

        assertEquals(
            listOf("最终内容", "补充内容"),
            message.chatVoiceReplyDraft()?.segments?.map { it.text },
        )
    }

    @Test
    fun reportsSpecificProviderAndServiceErrors() {
        assertEquals(
            ChatVoiceReplyErrorCode.MISSING_API_KEY,
            TTSProviderSetting.OpenAI(apiKey = "").configurationError()?.code,
        )
        assertEquals(
            ChatVoiceReplyErrorCode.BALANCE,
            classifyChatVoiceReplyErrorText("HTTP 402: balance is insufficient").code,
        )
        assertEquals(
            "未配置语音模型 API Key，请先填写 API Key。",
            ChatVoiceReplyError(ChatVoiceReplyErrorCode.MISSING_API_KEY).userMessage(),
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

    @Test
    fun failedVoiceToolIsDetectedAndStructuredReplyFallsBackToPlainText() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-error",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = "{}",
                    output = listOf(UIMessagePart.Text("rikkahub.chat_voice_reply.error:BALANCE")),
                ),
                UIMessagePart.Text("【语音条】我好想你【文本】但这句话仍然要显示"),
            ),
        )
        val parsed = requireNotNull(parseChatVoiceReply(message.toText()))

        assertEquals(
            ChatVoiceReplyErrorCode.BALANCE,
            message.parts.filterIsInstance<UIMessagePart.Tool>().single().chatVoiceReplyError()?.code,
        )
        val fallback = message
            .withChatVoiceReplyPlainText(parsed)
            .withChatVoiceReplyToolError(ChatVoiceReplyError(ChatVoiceReplyErrorCode.BALANCE))

        assertEquals("\n我好想你\n\n但这句话仍然要显示", fallback.toText())
        assertTrue(fallback.chatVoiceReply() == null)
        assertFalse(fallback.hasPendingChatVoiceReply())
        assertEquals(
            ChatVoiceReplyErrorCode.BALANCE,
            fallback.parts.filterIsInstance<UIMessagePart.Tool>().single().chatVoiceReplyError()?.code,
        )
    }

    @Test
    fun splitsVoiceReplyAtCallStyleSentenceBoundaries() {
        assertEquals(
            listOf("第一句。", "第二句！", "第三句？", "最后一句"),
            splitVoiceCallAudioTaggingSegments("第一句。第二句！第三句？最后一句"),
        )
    }
}
