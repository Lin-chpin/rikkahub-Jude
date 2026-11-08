package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlockTextStyleTest {
    private val density = Density(1f)
    private val parent = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)

    @Test
    fun `unlabelled indented styled HTML is recovered from a Markdown code block`() {
        val markdown = """
            <!-- generated reply -->
                <div style="background:#9d174d;color:#fff">
                    你好
                </div>
        """.trimIndent()
        val parsedMarkdown = Jsoup.parse(generateMarkdownHtml(markdown))

        assertNull("The user’s styled HTML should no longer be emitted as code", parsedMarkdown.selectFirst("pre > code"))
        assertNotNull(parsedMarkdown.selectFirst("div[style]"))
    }

    @Test
    fun `fenced HTML with and without a language and ordinary code remain code blocks`() {
        listOf(
            """
                ```
                    <div style="color:red">text</div>
                ```
            """.trimIndent(),
            """
                ```html
                    <div style="color:red">text</div>
                ```
            """.trimIndent(),
            "    <div>code example</div>",
        ).forEach { markdown ->
            assertNotNull(Jsoup.parse(generateMarkdownHtml(markdown)).selectFirst("pre > code"))
        }
    }

    @Test
    fun `CSS percentages and four-value border radius accept screenshot bubble values`() {
        assertEquals(0.82f, requireNotNull(parseCssPercentage("82%")), 0.001f)
        assertNull(parseCssPercentage("-2%"))
        assertNull(parseCssPercentage("82px"))
        assertEquals(
            listOf(12.dp, 12.dp, 12.dp, 12.dp),
            parseCssBorderRadius("12px"),
        )
        assertEquals(
            listOf(12.dp, 4.dp, 12.dp, 4.dp),
            parseCssBorderRadius("12px 4px"),
        )
        assertEquals(
            listOf(12.dp, 4.dp, 2.dp, 4.dp),
            parseCssBorderRadius("12px 4px 2px"),
        )
        assertEquals(
            listOf(12.dp, 12.dp, 2.dp, 12.dp),
            parseCssBorderRadius("12px 12px 2px 12px"),
        )
        assertNull(parseCssBorderRadius("12px invalid 2px 12px"))
    }

    @Test
    fun `reward card HTML accepts the outer container without an explicit font size`() {
        val styles = Jsoup.parseBodyFragment(REWARD_CARD_HTML).select("div[style]").map {
            requireNotNull(parseBlockTextStyle(it.attr("style"), density, parent))
        }

        assertEquals(4, styles.size)
        assertEquals(TextUnit.Unspecified, styles[0].fontSize)
        assertEquals(TextUnit.Unspecified, styles[0].lineHeight)
        assertEquals(24.sp, styles[1].fontSize)
        assertEquals(14.sp, styles[2].fontSize)
        assertEquals(11.sp, styles[3].fontSize)
    }

    @Test
    fun `styles unrelated to font size preserve unspecified size and line height`() {
        val declarations = listOf(
            "color:white",
            "background:rgb(250,157,59)",
            "font-weight:bold",
            "font-style:italic",
            "font-family:monospace",
            "letter-spacing:2px",
            "text-decoration:underline",
        )
        declarations.forEach { declaration ->
            val style = parseBlockTextStyle(declaration, density, parent)
            assertNotNull(declaration, style)
            assertFalse(declaration, requireNotNull(style).fontSize.isSpecified)
            assertFalse(declaration, style.lineHeight.isSpecified)
        }
    }

    @Test
    fun `merging child styles preserves inherited size and line height`() {
        val inherited = TextStyle(fontSize = 20.sp, lineHeight = 32.sp)
        val child = requireNotNull(parseBlockTextStyle("color:white;font-weight:bold", density, inherited))

        val resolved = inherited.merge(child)

        assertEquals(20.sp, resolved.fontSize)
        assertEquals(32.sp, resolved.lineHeight)
    }

    @Test
    fun `explicit line height works when font size is inherited`() {
        val style = requireNotNull(parseBlockTextStyle("font-style:italic;line-height:28px", density, parent))

        assertEquals(TextUnit.Unspecified, style.fontSize)
        assertEquals(28.sp, style.lineHeight)
    }

    @Test
    fun `unsupported font sizes retain parent typography`() {
        listOf("inherit", "unset", "not-a-size", "12qu", "").forEach { fontSize ->
            val child = requireNotNull(
                parseBlockTextStyle("font-weight:bold;font-size:$fontSize", density, parent)
            )
            val resolved = parent.merge(child)

            assertEquals(fontSize, parent.fontSize, resolved.fontSize)
            assertEquals(fontSize, parent.lineHeight, resolved.lineHeight)
        }
    }

    @Test
    fun `CSS pixel font size retains its layout size at a different density and font scale`() {
        val scaledDensity = Density(density = 3f, fontScale = 1.25f)
        val style = requireNotNull(parseBlockTextStyle("font-size:14px", scaledDensity, parent))

        with(scaledDensity) {
            assertEquals(42f, style.fontSize.toPx(), 0.001f)
        }
        assertEquals(style.fontSize * 1.2f, style.lineHeight)
    }

    @Test
    fun `explicit line height overrides the height derived from font size`() {
        val style = requireNotNull(parseBlockTextStyle("font-size:14px;line-height:30px", density, parent))

        assertEquals(14.sp, style.fontSize)
        assertEquals(30.sp, style.lineHeight)
    }

    @Test
    fun `empty and non text declarations leave inherited text unchanged`() {
        listOf("", "display:flex;padding:12px", "unknown:value").forEach { declaration ->
            assertNull(declaration, parseBlockTextStyle(declaration, density, parent))
        }
    }

    private companion object {
        val REWARD_CARD_HTML = """
            <div style="background:#fa9d3b;border-radius:10px;padding:12px 14px;max-width:240px;font-family:sans-serif;color:#fff;display:flex;align-items:center;box-shadow:0 2px 6px rgba(0,0,0,0.1);">
              <div style="font-size:24px;margin-right:10px;">🧧</div>
              <div>
                <div style="font-size:14px;font-weight:bold;">示例卡片标题</div>
                <div style="font-size:11px;opacity:0.85;margin-top:2px;">卡片说明</div>
              </div>
            </div>
        """.trimIndent()
    }
}
