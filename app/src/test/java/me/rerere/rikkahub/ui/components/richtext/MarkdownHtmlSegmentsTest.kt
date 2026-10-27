package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownHtmlSegmentsTest {
    @Test
    fun `nested HTML with comments and blank lines stays in one fragment`() {
        val source = """Intro paragraph

<!-- neutral comment -->
<div class="card">
  <div style="display:flex;gap:8px;overflow:hidden;text-overflow:ellipsis">
    <span>First</span>

    <span>Second</span>
  </div>
</div>
<!-- sibling separator -->
<section>Another block</section>

**Markdown tail**"""

        val segments = splitMarkdownHtmlSegments(source)
        val fragments = segments.filterIsInstance<MarkdownHtmlSegment.HtmlFragment>()

        assertEquals(source, segments.joinToString(separator = "") { it.content })
        assertEquals(1, fragments.size)
        assertTrue(fragments.single().isComplete)
        assertTrue(fragments.single().content.contains("<!-- neutral comment -->"))
        assertTrue(fragments.single().content.contains("<section>Another block</section>"))
        assertTrue(fragments.single().content.contains("\n\n    <span>Second</span>"))
    }

    @Test
    fun `fenced HTML remains Markdown code`() {
        val source = """```html
<div>
  <span>literal</span>
</div>
```"""

        val segments = splitMarkdownHtmlSegments(source)

        assertEquals(listOf(MarkdownHtmlSegment.Markdown(source)), segments)
    }

    @Test
    fun `standard and obsolete HTML elements use the WebView fragment path`() {
        val source = """<center>Centered</center>

<base href="https://invalid.example/">

<fieldset><legend>Options</legend><menu><li>One</li></menu></fieldset>"""

        val segments = splitMarkdownHtmlSegments(source)
        val fragment = segments.filterIsInstance<MarkdownHtmlSegment.HtmlFragment>().single()

        assertEquals(source, fragment.content)
        assertTrue(fragment.isComplete)
        assertEquals(listOf(fragment), segments)
    }

    @Test
    fun `unfinished streamed fragment remains protected as HTML`() {
        val source = "Before\n\n<article class=\"streaming\"><div>partial"

        val segments = splitMarkdownHtmlSegments(source)
        val fragment = segments.filterIsInstance<MarkdownHtmlSegment.HtmlFragment>().single()

        assertEquals(source, segments.joinToString(separator = "") { it.content })
        assertFalse(fragment.isComplete)
        assertTrue(fragment.content.startsWith("<article"))
    }

    @Test
    fun `fragment and full document receive the same responsive preview policy`() {
        val fragment = prepareHtmlPreviewDocument(
            "<div style=\"display:flex;gap:8px;text-overflow:ellipsis\">Neutral</div>",
            allowInlineScripts = false,
        )
        val document = prepareHtmlPreviewDocument(
            """<!doctype html><html><head><meta name="viewport" content="width=980"><title>Neutral</title></head><body><div>Neutral</div></body></html>""",
            allowInlineScripts = false,
        )
        val viewport = Regex("""<meta name="viewport" content="width=device-width, initial-scale=1">""")

        assertEquals(1, viewport.findAll(fragment).count())
        assertEquals(1, viewport.findAll(document).count())
        assertTrue(fragment.contains("width:100%;max-width:100%"))
        assertTrue(document.contains("width:100%;max-width:100%"))
        assertTrue(fragment.contains("display:flex;gap:8px;text-overflow:ellipsis"))
        assertTrue(fragment.contains("script-src 'none'"))
        assertTrue(prepareHtmlPreviewDocument("<div>Neutral</div>", true).contains("script-src 'unsafe-inline'"))
    }
}
