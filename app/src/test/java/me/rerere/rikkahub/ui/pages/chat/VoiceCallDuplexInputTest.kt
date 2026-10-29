package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallDuplexInputTest {
    @Test
    fun `permission loss keeps partial text but mute and call end clear it`() {
        assertFalse(shouldClearVoiceCallPendingText(enabled = true, permissionGranted = false, muted = false))
        assertTrue(shouldClearVoiceCallPendingText(enabled = true, permissionGranted = true, muted = false))
        assertTrue(shouldClearVoiceCallPendingText(enabled = true, permissionGranted = false, muted = true))
        assertTrue(shouldClearVoiceCallPendingText(enabled = false, permissionGranted = false, muted = false))
    }
}
