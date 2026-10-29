package me.rerere.rikkahub.data.voice

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallVisualRequestTest {
    @Test
    fun `configured OCR uses recognized text even when main model accepts images`() {
        val frames = listOf(
            VoiceCallVisualFrame(VoiceCallVisualSource.SCREEN, byteArrayOf(1, 2)),
        )

        val parts = voiceCallVisualRequestParts(
            frames = frames,
            useOcrModel = true,
            mainModelSupportsImages = true,
            recognizedDescriptions = listOf(VoiceCallVisualSource.SCREEN to "页面标题"),
        )

        assertEquals(1, parts.size)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("屏幕共享画面：页面标题"))
        assertFalse(parts.any { it is UIMessagePart.Image })
    }

    @Test
    fun `main vision model receives captured frames when no OCR is selected`() {
        val frame = VoiceCallVisualFrame(VoiceCallVisualSource.CAMERA, byteArrayOf(1, 2))

        val parts = voiceCallVisualRequestParts(
            frames = listOf(frame),
            useOcrModel = false,
            mainModelSupportsImages = true,
            recognizedDescriptions = emptyList(),
        )

        assertEquals(1, parts.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue(parts.none { it is UIMessagePart.Text && it.text.contains("OCR 模型") })
    }
}
