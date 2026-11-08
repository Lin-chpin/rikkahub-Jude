package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.*
import org.junit.Test

class VoiceCallDuplexInputStateTest {
    @Test
    fun terminalAsrFailureClearsThePartialAndReleasesTheVisualTurn() {
        val state = VoiceCallDuplexInputState()
        state.pendingText = "尚未结束的分句"
        var visualSpeechStarted = true

        state.finishWithError("连接失败") { visualSpeechStarted = false }

        assertEquals("", state.pendingText)
        assertEquals("连接失败", state.error)
        assertFalse(visualSpeechStarted)
    }

    @Test
    fun terminalAsrFailurePreservesProviderSpecificMessage() {
        assertEquals(
            "ElevenLabs Scribe 鉴权失败，请检查 API Key 和语音识别权限。",
            voiceCallTerminalAsrErrorMessage("ElevenLabs Scribe 鉴权失败，请检查 API Key 和语音识别权限。"),
        )
        assertEquals(
            "语音识别连接失败，请重试或检查 ASR 配置。",
            voiceCallTerminalAsrErrorMessage(null),
        )
    }
}
