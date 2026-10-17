package me.rerere.rikkahub.service

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
    fun `connection and hangup events belong to transient runtime instructions`() {
        val active = buildVoiceCallRuntimeContext(VoiceCallRuntimeState.ACTIVE, includeConnectionEvent = true).systemPrompt
        val ended = buildVoiceCallRuntimeContext(VoiceCallRuntimeState.ENDED, includeConnectionEvent = true).systemPrompt
        val inactive = buildVoiceCallRuntimeContext(VoiceCallRuntimeState.INACTIVE, includeConnectionEvent = true).systemPrompt

        assertTrue(active.contains("[VOICE_CALL_RUNTIME_EVENT]"))
        assertTrue(active.contains("first spoken message after connection"))
        assertTrue(ended.contains("[VOICE_CALL_RUNTIME_EVENT]"))
        assertTrue(ended.contains("first normal text message after hangup"))
        assertFalse(inactive.contains("[VOICE_CALL_RUNTIME_EVENT]"))
        assertFalse(buildVoiceCallRuntimeContext(VoiceCallRuntimeState.ACTIVE).systemPrompt.contains("[VOICE_CALL_RUNTIME_EVENT]"))
        assertFalse(buildVoiceCallRuntimeContext(VoiceCallRuntimeState.ENDED).systemPrompt.contains("[VOICE_CALL_RUNTIME_EVENT]"))
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
