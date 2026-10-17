package me.rerere.rikkahub.data.voice

import me.rerere.ai.ui.ChatVoiceAudioSegment
import me.rerere.ai.ui.ChatVoiceReplySegment
import me.rerere.ai.ui.ChatVoiceReplySegmentType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVoiceReplyEditingTest {
    @Test
    fun editingOrDeletingSurroundingTextPreservesRecordedVoice() {
        val voice = ChatVoiceReplySegment(
            type = ChatVoiceReplySegmentType.VOICE,
            text = "[softly]我在这里。",
            audioSegments = listOf(ChatVoiceAudioSegment("我在这里。", "file:///voice.mp3", "MP3")),
        )
        val text = ChatVoiceReplySegment(ChatVoiceReplySegmentType.TEXT, "原来的说明")
        val original = UIMessage.assistant("").withChatVoiceReply(ParsedChatVoiceReply(listOf(text, voice, text)))
        val edited = original.withEditedChatVoiceReplyText(listOf(UIMessagePart.Text("新的说明\n\n我在这里。")))
        assertEquals(listOf("新的说明", voice.text), edited.chatVoiceReply()?.segments?.map { it.text })
        assertEquals(voice, edited.chatVoiceReply()?.segments?.last())
        val deletedText = original.withEditedChatVoiceReplySegments(listOf(voice))
        assertEquals(listOf(voice), deletedText.chatVoiceReply()?.segments)
        assertEquals("我在这里。", deletedText.toText())
        assertEquals(3, original.chatVoiceReply()?.segments?.size)
    }

    @Test
    fun changingSpokenWordsCannotRetainAudioSayingSomethingElse() {
        val voice = ChatVoiceReplySegment(
            ChatVoiceReplySegmentType.VOICE, "旧语音。",
            listOf(ChatVoiceAudioSegment("旧语音。", "file:///voice.mp3", "MP3")),
        )
        val original = UIMessage.assistant("").withChatVoiceReply(ParsedChatVoiceReply(listOf(voice)))
        val edited = original.withEditedChatVoiceReplySegments(listOf(voice.copy(text = "新正文。")))
        val segment = edited.chatVoiceReply()!!.segments.single()
        assertEquals(ChatVoiceReplySegmentType.TEXT, segment.type)
        assertTrue(segment.audioSegments.isEmpty())
    }
}
