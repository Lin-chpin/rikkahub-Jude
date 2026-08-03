package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompressionVoiceCallTest {
    @Test
    fun `voice call transcript is explicitly marked for compression`() {
        val source = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("spoken text")),
        )

        val projected = source.forConversationCompression(isVoiceCallTranscript = true)
        val text = projected.toText()

        assertEquals(MessageRole.USER, projected.role)
        assertTrue(text.contains("[VOICE_CALL_TRANSCRIPT_SEGMENT]"))
        assertTrue(text.contains("not a normal chat message"))
        assertTrue(text.contains("spoken text"))
    }

    @Test
    fun `normal chat message is not changed for compression`() {
        val source = UIMessage.user("normal text")

        assertSame(source, source.forConversationCompression(isVoiceCallTranscript = false))
    }
}
