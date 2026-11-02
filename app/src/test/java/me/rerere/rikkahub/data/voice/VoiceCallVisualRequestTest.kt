package me.rerere.rikkahub.data.voice

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallVisualRequestTest {
    @Test
    fun `recognized descriptions stay ordered and never resend captured images`() {
        val parts = voiceCallVisualRequestParts(
            listOf(
                VoiceCallVisualSource.SCREEN to "页面标题",
                VoiceCallVisualSource.CAMERA to "桌面",
            ),
        )

        assertEquals(1, parts.size)
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue(text.contains("第1张（屏幕共享画面）：页面标题"))
        assertTrue(text.contains("第2张（摄像头画面）：桌面"))
        assertFalse(parts.any { it is UIMessagePart.Image })
    }

    @Test
    fun `no successful recognition produces no visual request part`() {
        assertTrue(voiceCallVisualRequestParts(emptyList()).isEmpty())
    }
}
