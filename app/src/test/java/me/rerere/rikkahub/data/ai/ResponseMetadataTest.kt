package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ResponseMetadataTest {
    @Test fun `diagnostic response preserves usage but cannot persist private content`() {
        val source = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("private-screen-text"), UIMessagePart.Image("private-screen-image")),
            usage = TokenUsage(promptTokens = 120, completionTokens = 20, cachedTokens = 80, totalTokens = 140),
            translation = "private-translation", voiceCallTranslations = mapOf("sentence" to "private-call-text"),
        )
        val metadata = source.responseMetadata()
        assertEquals(source.usage, metadata.usage)
        assertEquals(source.createdAt, metadata.createdAt)
        assertEquals(source.finishedAt, metadata.finishedAt)
        assertTrue(metadata.parts.isEmpty())
        assertTrue(metadata.annotations.isEmpty())
        assertNull(metadata.translation)
        assertTrue(metadata.voiceCallTranslations.isEmpty())
        assertFalse(Json.encodeToString(UIMessage.serializer(), metadata).contains("private-"))
    }
}
