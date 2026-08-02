package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallRuntimePromptTest {
    @Test
    fun `active state identifies connected call and applies soft reply limit`() {
        val context = buildVoiceCallRuntimeContext(VoiceCallRuntimeState.ACTIVE)
        val prompt = context.systemPrompt

        assertTrue(prompt.contains("state=ACTIVE"))
        assertTrue(prompt.contains("regardless of whether the user or the assistant initiated it"))
        assertTrue(prompt.contains("within 200 Chinese characters"))
        assertTrue(prompt.contains("do not generate a longer reply and truncate it"))
        assertTrue(prompt.contains("spoken after the voice call connected"))
        assertTrue(prompt.contains("Do not announce or explain this runtime state"))
    }

    @Test
    fun `normal state explicitly marks previous call as ended`() {
        val context = buildVoiceCallRuntimeContext(VoiceCallRuntimeState.INACTIVE)
        val prompt = context.systemPrompt

        assertTrue(prompt.contains("state=INACTIVE"))
        assertTrue(prompt.contains("Any earlier call has ended"))
        assertFalse(prompt.contains("within 200 Chinese characters"))
    }

    @Test
    fun `ended state decorates the first post-hangup user message only for request context`() {
        val context = buildVoiceCallRuntimeContext(VoiceCallRuntimeState.ENDED)
        val originalMessage = UIMessage.user("Are you still there?")
        val requestMessage = originalMessage.withVoiceCallEndedEventForRequest()

        assertTrue(context.systemPrompt.contains("state=ENDED"))
        assertTrue(context.systemPrompt.contains("first normal text message after hangup"))
        assertEquals(MessageRole.USER, requestMessage.role)
        assertEquals("Are you still there?", originalMessage.toText())
        assertTrue(requestMessage.toText().contains("state=ENDED"))
        assertTrue(requestMessage.toText().contains("[USER_MESSAGE]"))
        assertTrue(requestMessage.toText().endsWith("Are you still there?"))
    }

    @Test
    fun `request modes map to stable call states`() {
        assertEquals(
            VoiceCallRuntimeState.ACTIVE,
            ChatRequestMode.VoiceCall.defaultVoiceCallRuntimeState(),
        )
        assertEquals(
            VoiceCallRuntimeState.INACTIVE,
            ChatRequestMode.Normal.defaultVoiceCallRuntimeState(),
        )
    }
}
