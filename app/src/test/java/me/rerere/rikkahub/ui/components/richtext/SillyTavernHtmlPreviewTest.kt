package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SillyTavernHtmlPreviewTest {
    @Test
    fun `long HTML uses the fixed preview height until expanded`() {
        assertEquals(220.dp, htmlPreviewVisibleHeight(640.dp, expanded = false))
        assertEquals(640.dp, htmlPreviewVisibleHeight(640.dp, expanded = true))
    }

    @Test
    fun `short HTML keeps its natural height`() {
        assertEquals(96.dp, htmlPreviewVisibleHeight(96.dp, expanded = false))
    }

    @Test
    fun `empty measurement still keeps a visible WebView`() {
        assertEquals(1.dp, htmlPreviewVisibleHeight(0.dp, expanded = true))
    }
}
