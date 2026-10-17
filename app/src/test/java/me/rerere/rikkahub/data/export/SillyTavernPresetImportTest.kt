package me.rerere.rikkahub.data.export

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.transformMessages
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernPresetImportTest {
    @Test
    fun importsOrderedPromptRolesHistoryDepthAndSafeModelCondition() {
        val preset = """
            {
              "prompts": [
                {"identifier":"opening","name":"Opening","content":"opening","role":"system"},
                {"identifier":"main","name":"Main","content":"main","role":"user"},
                {"identifier":"charDescription","name":"Char Description","content":"","role":"system"},
                {"identifier":"chatHistory","name":"Chat History","content":"","role":"system"},
                {"identifier":"closing","name":"Closing","content":"closing","role":"system"},
                {"identifier":"depth","name":"Depth","content":"depth","role":"assistant","injection_position":1,"injection_depth":0},
                {"identifier":"conditional","name":"Conditional","content":"conditional","role":"system","spreset_condition_enabled":true,"spreset_condition_script":"({ model }) => /3\\.1/i.test(model)"}
              ],
              "prompt_order": [{"character_id":100001,"order":[
                {"identifier":"opening","enabled":true},
                {"identifier":"main","enabled":true},
                {"identifier":"charDescription","enabled":true},
                {"identifier":"chatHistory","enabled":true},
                {"identifier":"closing","enabled":true},
                {"identifier":"depth","enabled":true},
                {"identifier":"conditional","enabled":true}
              ]}]
            }
        """.trimIndent()

        val lorebook = LorebookSerializer.tryImportSillyTavernPreset(preset, "preset")
        assertNotNull(lorebook)
        lorebook!!

        val opening = lorebook.entries.first { it.name == "Opening" }
        val main = lorebook.entries.first { it.name == "Main" }
        val character = lorebook.entries.first { it.name == "Char Description" }
        val closing = lorebook.entries.first { it.name == "Closing" }
        val depth = lorebook.entries.first { it.name == "Depth" }
        val conditional = lorebook.entries.first { it.name == "Conditional" }

        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, opening.position)
        assertEquals(MessageRole.USER, main.role)
        assertTrue(main.preserveRole)
        assertEquals("{{assistant_system_prompt}}", character.content)
        assertEquals("charDescription", character.tavernPresetSlot)
        assertEquals(InjectionPosition.AT_DEPTH, closing.position)
        assertEquals(0, closing.injectDepth)
        assertEquals(0, depth.injectDepth)
        assertEquals("3\\.1", conditional.modelConditionRegex)
        assertTrue(conditional.modelConditionIgnoreCase)

        val messages = transformMessages(
            messages = listOf(UIMessage.system("base"), UIMessage.user("latest")),
            assistant = Assistant(lorebookIds = setOf(lorebook.id)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
            modelId = "provider-model-3.1",
        )

        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.SYSTEM, MessageRole.USER, MessageRole.SYSTEM,
                MessageRole.USER, MessageRole.SYSTEM, MessageRole.ASSISTANT),
            messages.map { it.role },
        )
        assertTrue(messages.any { it.toText().contains("conditional") })
        assertTrue(messages.any { it.toText() == "{{assistant_system_prompt}}" })
        assertEquals("depth", messages.last().toText())
    }

    @Test
    fun legacyDepthZeroStillInsertsBeforeLatestMessage() {
        val lorebook = Lorebook(
            entries = listOf(
                PromptInjection.RegexInjection(
                    name = "legacy",
                    constantActive = true,
                    position = InjectionPosition.AT_DEPTH,
                    injectDepth = 0,
                    content = "legacy",
                ),
            ),
        )
        val messages = transformMessages(
            messages = listOf(UIMessage.system("base"), UIMessage.user("latest")),
            assistant = Assistant(lorebookIds = setOf(lorebook.id)),
            modeInjections = emptyList(),
            lorebooks = listOf(lorebook),
        )

        assertEquals(listOf("base", "legacy", "latest"), messages.map { it.toText() })
    }
}
