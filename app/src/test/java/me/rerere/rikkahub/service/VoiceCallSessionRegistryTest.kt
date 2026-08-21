package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallSessionRegistryTest {
    @Test
    fun tracksMultipleIndependentCallSessions() {
        val first = "test-first"
        val second = "test-second"

        VoiceCallSessionRegistry.unregister(first)
        VoiceCallSessionRegistry.unregister(second)
        assertFalse(VoiceCallSessionRegistry.isActive())

        VoiceCallSessionRegistry.register(first)
        VoiceCallSessionRegistry.register(second)
        assertTrue(VoiceCallSessionRegistry.isActive())

        VoiceCallSessionRegistry.unregister(first)
        assertTrue(VoiceCallSessionRegistry.isActive())
        VoiceCallSessionRegistry.unregister(second)
        assertFalse(VoiceCallSessionRegistry.isActive())
    }
}
