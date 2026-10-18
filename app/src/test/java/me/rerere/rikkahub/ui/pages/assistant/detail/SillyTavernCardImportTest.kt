package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.model.InjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernCardImportTest {
    @Test
    fun importsEmbeddedCharacterBookAsProjectLorebook() {
        val book = Json.parseToJsonElement(
            """
            {
              "name":"World",
              "scan_depth":8,
              "entries":[
                {"comment":"Always","content":"static lore","constant":true,"enabled":true,
                 "position":"after_char","keys":[],"extensions":{"depth":0,"role":0}},
                {"comment":"Keyword","content":"triggered lore","constant":false,"enabled":false,
                 "position":"before_char","keys":["\\bkeyword\\b"],"use_regex":true,"insertion_order":12,
                 "extensions":{"case_sensitive":true,"scan_depth":6,"role":1}}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val lorebook = LorebookSerializer.tryImportSillyTavernCharacterBook(book, "Card")
        assertNotNull(lorebook)
        lorebook!!
        assertEquals("World", lorebook.name)
        assertEquals(2, lorebook.entries.size)
        assertTrue(lorebook.entries[0].constantActive)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, lorebook.entries[0].position)
        assertEquals(0, lorebook.entries[0].injectDepth)
        assertEquals(MessageRole.SYSTEM, lorebook.entries[0].role)
        assertFalse(lorebook.entries[1].enabled)
        assertEquals(12, lorebook.entries[1].priority)
        assertEquals(InjectionPosition.BEFORE_SYSTEM_PROMPT, lorebook.entries[1].position)
        assertEquals(listOf("\\bkeyword\\b"), lorebook.entries[1].keywords)
        assertTrue(lorebook.entries[1].useRegex)
        assertTrue(lorebook.entries[1].caseSensitive)
        assertEquals(6, lorebook.entries[1].scanDepth)
        assertEquals(MessageRole.USER, lorebook.entries[1].role)
    }

    @Test
    fun removesTemplatesAndPresentationMarkupButKeepsText() {
        val clean = cleanTavernCardText(
            "<% run frontend code %><p>{{char}}对{{user}}说{{getvar::state}}你好</p>" +
                "<script>discard()</script><style>.discard {}</style>" +
                "```html<div>discard</div>```",
            "角色",
        )

        assertEquals("角色对用户说你好", clean)
        assertFalse(clean.contains("<"))
        assertFalse(clean.contains("{{"))
    }
}
