package me.rerere.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.toInitialMessageNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernCardImportTest {
    @Test
    fun keepsGreetingOutOfSystemPromptAndUsesAlternateForPreviewPlaceholder() {
        val data = Json.parseToJsonElement(
            """
            {
              "first_mes":"<MusicPlayer_1/>\n系统：展示开局预览",
              "alternate_greetings":["{{char}}的真实开场","其他备选开场"],
              "description":"角色人设",
              "scenario":"故事场景"
            }
            """.trimIndent(),
        ).jsonObject

        val assistant = createAssistant(data, "角色")

        assertEquals(1, assistant.presetMessages.size)
        assertEquals(MessageRole.ASSISTANT, assistant.presetMessages.single().role)
        assertEquals("角色的真实开场", assistant.presetMessages.single().toText())
        assertEquals(listOf("其他备选开场"), assistant.alternateGreetings)
        val greetingNode = assistant.toInitialMessageNodes().single()
        assertEquals(2, greetingNode.messages.size)
        assertEquals("角色的真实开场", greetingNode.currentMessage.toText())
        assertEquals("其他备选开场", greetingNode.messages[1].toText())
        assertTrue(assistant.systemPrompt.contains("角色人设"))
        assertTrue(assistant.systemPrompt.contains("故事场景"))
        assertFalse(assistant.systemPrompt.contains("真实开场"))
        assertFalse(assistant.systemPrompt.contains("其他备选开场"))
        assertFalse(assistant.systemPrompt.contains("展示开局预览"))
    }

    @Test
    fun keepsDefaultGreetingFirstAndStoresAllAlternatesForSelection() {
        val data = Json.parseToJsonElement(
            """
            {
              "first_mes":"默认开场",
              "alternate_greetings":["第二开场","第三开场"]
            }
            """.trimIndent(),
        ).jsonObject

        val assistant = createAssistant(data, "角色")
        val greetingNode = assistant.toInitialMessageNodes().single()

        assertEquals("默认开场", greetingNode.currentMessage.toText())
        assertEquals(listOf("第二开场", "第三开场"), assistant.alternateGreetings)
        assertEquals(listOf("默认开场", "第二开场", "第三开场"), greetingNode.messages.map { it.toText() })
    }

    @Test
    fun promotesAlwaysOnSystemCharacterBookEntriesWithoutDuplicatingLorebook() {
        val lorebook = Lorebook(
            name = "Card lore",
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "Character profile",
                    enabled = true,
                    position = InjectionPosition.AFTER_SYSTEM_PROMPT,
                    content = "Always-on character description",
                    role = MessageRole.SYSTEM,
                    constantActive = true,
                ),
                PromptInjection.RegexInjection(
                    name = "Conditional lore",
                    enabled = true,
                    position = InjectionPosition.AFTER_SYSTEM_PROMPT,
                    content = "Keyword-triggered details",
                    role = MessageRole.SYSTEM,
                    keywords = listOf("trigger"),
                ),
            ),
        )

        val imported = attachCharacterBook(
            Assistant(name = "Card", systemPrompt = "Base prompt"),
            lorebook,
        )

        assertTrue(imported.assistant.systemPrompt.contains("Always-on character description"))
        assertFalse(imported.assistant.systemPrompt.contains("Keyword-triggered details"))
        assertEquals(listOf("Conditional lore"), imported.lorebook?.entries?.map { it.name })
    }

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
