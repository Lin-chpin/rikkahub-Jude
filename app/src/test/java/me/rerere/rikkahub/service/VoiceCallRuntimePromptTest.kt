package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallRuntimePromptTest {
    @Test
    fun `active state identifies connected call and applies soft reply limit`() {
        val prompt = buildVoiceCallRuntimeStatePrompt(ChatRequestMode.VoiceCall)

        assertTrue(prompt.contains("state=ACTIVE"))
        assertTrue(prompt.contains("within 200 Chinese characters"))
        assertTrue(prompt.contains("do not generate a longer reply and truncate it"))
        assertTrue(prompt.contains("spoken after the voice call connected"))
        assertTrue(prompt.contains("Do not announce or explain this runtime state"))
    }

    @Test
    fun `normal state explicitly marks previous call as ended`() {
        val prompt = buildVoiceCallRuntimeStatePrompt(ChatRequestMode.Normal)

        assertTrue(prompt.contains("state=INACTIVE"))
        assertTrue(prompt.contains("Any earlier call has ended"))
        assertFalse(prompt.contains("within 200 Chinese characters"))
    }
}
