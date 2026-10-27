package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageSegmentsTest {
    @Test
    fun `raw HTML stays in one paragraph bubble across blank lines and comments`() {
        val source = """Before HTML

<!-- neutral comment -->
<div class="card">
  <div style="display:flex;gap:8px">
    <span>First</span>

    <span>Second</span>
  </div>
</div>

After HTML"""

        val segments = source.splitAssistantTextSegments()
        val htmlParagraphs = segments.filterIsInstance<AssistantTextSegment.Paragraph>()
            .filter { it.text.startsWith("<!-- neutral comment -->") }

        assertEquals(3, segments.size)
        assertEquals(1, htmlParagraphs.size)
        assertTrue(htmlParagraphs.single().text.contains("\n\n    <span>Second</span>"))
        assertTrue(htmlParagraphs.single().text.endsWith("</div>"))
    }

    @Test
    fun `fenced HTML remains a code block in message bubbles`() {
        val source = """```html
<div>
  <span>literal</span>
</div>
```"""

        val segments = source.splitAssistantTextSegments()

        assertEquals(1, segments.size)
        assertTrue(segments.single() is AssistantTextSegment.CodeBlock)
    }
}
