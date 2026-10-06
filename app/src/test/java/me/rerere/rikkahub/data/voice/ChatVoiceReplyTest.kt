package me.rerere.rikkahub.data.voice

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.isElevenLabsV4Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVoiceReplyTest {
    @Test
    fun parsesMixedVoiceAndTextToolArgumentsInOrder() {
        val parsed = requireNotNull(
            parseChatVoiceReplyArguments(Json.parseToJsonElement("""
                {"segments":[
                  {"type":"text","text":"先看说明"},
                  {"type":"voice","text":"我陪你试试。"},
                  {"type":"text","text":"接着检查配置"},
                  {"type":"voice","text":"我等你。"}
                ]}
            """))
        )

        assertEquals(
            listOf(
                ChatVoiceReplySegmentType.TEXT,
                ChatVoiceReplySegmentType.VOICE,
                ChatVoiceReplySegmentType.TEXT,
                ChatVoiceReplySegmentType.VOICE,
            ),
            parsed.segments.map { it.type },
        )
        assertEquals(listOf("先看说明", "我陪你试试。", "接着检查配置", "我等你。"), parsed.segments.map { it.text })
    }

    @Test
    fun keepsInlineTagsInVoiceBubbleButHidesThemFromMainReplyText() {
        val parsed = requireNotNull(parseChatVoiceReplyArguments(Json.parseToJsonElement("""
            {"segments":[
              {"type":"voice","text":"[softly, affectionate]我想你。"},
              {"type":"text","text":"普通说明 [literal]"}
            ]}
        """)))
        val materialized = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()).withChatVoiceReply(parsed)

        assertEquals("[softly, affectionate]我想你。", parsed.segments.first().text)
        assertEquals("我想你。\n\n普通说明 [literal]", parsed.plainText)
        assertEquals("[softly, affectionate]我想你。", materialized.chatVoiceReply()?.segments?.first()?.text)
        assertFalse(materialized.toText().contains("[softly, affectionate]"))
    }

    @Test
    fun supportsOneWholeVoiceSegmentAndRejectsTextOnlyArguments() {
        val wholeVoice = requireNotNull(parseChatVoiceReplyArguments(Json.parseToJsonElement(
            """{"segments":[{"type":"voice","text":"第一句。第二句！第三句？"}]}"""
        )))
        assertEquals(ChatVoiceReplySegmentType.VOICE, wholeVoice.segments.single().type)
        assertNull(parseChatVoiceReplyArguments(Json.parseToJsonElement(
            """{"segments":[{"type":"text","text":"普通回复"}]}"""
        )))
    }

    @Test
    fun sendsThreeSentenceMiniMaxVoiceSegmentAsOneRequestChunk() {
        val text = "第一句。第二句！第三句？"
        assertEquals(listOf(text), miniMaxChatVoiceReplyChunks(text).map { it.text })
    }

    @Test
    fun sendsElevenLabsV4VoiceSegmentAsOneRequestWithinModelLimit() {
        val text = "第一句。第二句！第三句？"
        assertEquals(listOf(text), elevenLabsV4ChatVoiceReplyChunks(text).map { it.text })
        assertTrue(TTSProviderSetting.ElevenLabs(model = "eleven_v4_turbo").isElevenLabsV4Family())
        assertTrue(runCatching { elevenLabsV4ChatVoiceReplyChunks("字".repeat(10_001)) }.isFailure)
    }

    @Test
    fun splitsMiniMaxVoiceOnlyWhenTheSingleRequestLimitIsExceeded() {
        val text = "这是一段很长的语音。".repeat(1_100)
        val chunks = miniMaxChatVoiceReplyChunks(text)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length < 10_000 })
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun rejectsMissingOrInvalidToolArguments() {
        assertNull(parseChatVoiceReplyArguments(Json.parseToJsonElement("{}")))
        assertNull(parseChatVoiceReplyArguments(Json.parseToJsonElement(
            """{"segments":[{"type":"voice","text":""}]}"""
        )))
    }

    @Test
    fun plainTextReplyNeedsNoVoiceTool() {
        val message = UIMessage.assistant("这是普通文字回复")
        assertNull(message.chatVoiceReplyDraft())
        assertNull(findChatVoiceReplyMaterializationTarget(listOf(message), emptySet()))
    }

    @Test
    fun materializationKeepsReadableTextAndOrderedSegments() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("模型流式前缀")),
        )
        val parsed = requireNotNull(parseChatVoiceReplyArguments(Json.parseToJsonElement(
            """{"segments":[{"type":"voice","text":"我好想你"},{"type":"text","text":"你刚刚没听见吧"}]}"""
        )))
        val materialized = message.withChatVoiceReply(parsed)

        assertEquals("我好想你\n\n你刚刚没听见吧", materialized.toText())
        assertEquals(2, materialized.chatVoiceReply()?.segments?.size)
    }

    @Test
    fun findsReplyInExecutedToolArguments() {
        val existingMessage = UIMessage.user("请发一条语音")
        val toolReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-1",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = """{"segments":[{"type":"voice","text":"我好想你"},{"type":"text","text":"你刚刚没听见吧"}]}""",
                    output = listOf(UIMessagePart.Text(CHAT_VOICE_REPLY_TOOL_RESULT_PROMPT)),
                ),
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
    fun materializesToolSegmentsOnTheFinalVisibleAssistantMessage() {
        val existingMessage = UIMessage.user("请发一条语音")
        val firstToolReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-first",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = """{"segments":[{"type":"voice","text":"第一次流式输出"}]}""",
                    output = listOf(UIMessagePart.Text("done")),
                ),
            ),
        )
        val finalReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("额外普通文本")),
        )

        val target = findChatVoiceReplyMaterializationTarget(
            messages = listOf(existingMessage, firstToolReply, finalReply),
            generationBaseMessageIds = setOf(existingMessage.id),
        )

        assertEquals(finalReply.id, target?.replyMessage?.id)
        assertEquals(listOf("第一次流式输出"), target?.parsedReply?.segments?.map { it.text })
    }

    @Test
    fun doesNotTreatFollowingPlainTextAsMissingVoiceArguments() {
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
        val plainReply = UIMessage.assistant("这是普通回复")

        val target = findChatVoiceReplyMaterializationTarget(
            messages = listOf(existingMessage, toolReply, plainReply),
            generationBaseMessageIds = setOf(existingMessage.id),
        )

        assertNull(target)
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
    fun readsToolArgumentsInsteadOfSurroundingTextParts() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("流式前缀"),
                UIMessagePart.Tool(
                    toolCallId = "voice-repeat",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = """{"segments":[{"type":"voice","text":"最终内容"},{"type":"text","text":"补充内容"}]}""",
                    output = listOf(UIMessagePart.Text("done")),
                ),
                UIMessagePart.Text("后续流式文本"),
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
    fun hidesToolReplyUntilVoiceReplyIsMaterialized() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-2",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = """{"segments":[{"type":"voice","text":"先隐藏这段语音正文"}]}""",
                    output = listOf(UIMessagePart.Text("done")),
                ),
            ),
        )

        assertTrue(message.hasPendingChatVoiceReply())
        val parsed = requireNotNull(message.chatVoiceReplyDraft())
        assertFalse(message.withChatVoiceReply(parsed).hasPendingChatVoiceReply())
    }

    @Test
    fun failedVoiceToolRetainsReadableReplyFromArguments() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "voice-error",
                    toolName = CHAT_VOICE_REPLY_TOOL_NAME,
                    input = """{"segments":[{"type":"voice","text":"我好想你"},{"type":"text","text":"但这句话仍然要显示"}]}""",
                    output = listOf(UIMessagePart.Text("rikkahub.chat_voice_reply.error:BALANCE")),
                ),
            ),
        )
        val parsed = requireNotNull(message.chatVoiceReplyDraft())

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
