package me.rerere.ai.provider.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.SYSTEM_PROMPT_DYNAMIC_SECTION
import me.rerere.ai.ui.SYSTEM_PROMPT_SECTION_METADATA_KEY
import me.rerere.ai.ui.SYSTEM_PROMPT_STABLE_SECTION
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClaudeSystemMessagesTest {
    private val provider = ClaudeProvider(OkHttpClient())
    private val params = TextGenerationParams(Model(modelId = "claude-sonnet-4-5"))

    @Test
    fun `collects all system messages non-blank text parts in order and excludes them from messages`() {
        val setting = ProviderSetting.Claude(apiKey = "test", promptCaching = false)
        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(
                    UIMessagePart.Text("base prompt"),
                    UIMessagePart.Text("   "),
                    UIMessagePart.Image("https://example.invalid/ignored.png"),
                    UIMessagePart.Text("persona rules"),
                ),
            ),
            UIMessage.user("hello"),
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(
                    UIMessagePart.Text(""),
                    UIMessagePart.Text("[VOICE_CALL_RUNTIME_STATE] state=ACTIVE"),
                ),
            ),
            UIMessage.assistant("hi"),
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text("late worldbook instruction")),
            ),
        )

        val request = provider.buildMessageRequest(setting, messages, params)
        val systemBlocks = request.getValue("system").jsonArray
        assertEquals(
            listOf(
                "base prompt",
                "persona rules",
                "[VOICE_CALL_RUNTIME_STATE] state=ACTIVE",
                "late worldbook instruction",
            ),
            systemBlocks.map { it.jsonObject.getValue("text").jsonPrimitive.content },
        )
        systemBlocks.forEach { block ->
            assertEquals("text", block.jsonObject.getValue("type").jsonPrimitive.content)
            assertFalse(block.jsonObject.containsKey("cache_control"))
        }

        val serializedMessages = request.getValue("messages").jsonArray
        assertEquals(
            listOf("user", "assistant"),
            serializedMessages.map { it.jsonObject.getValue("role").jsonPrimitive.content },
        )
    }

    @Test
    fun `omits system key when system messages contain only blank text`() {
        val setting = ProviderSetting.Claude(apiKey = "test", promptCaching = true)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("  \n\t "))),
            UIMessage.user("hello"),
        )

        val request = provider.buildMessageRequest(setting, messages, params)
        assertFalse(request.containsKey("system"))
    }

    @Test
    fun `places cache_control on last system text part when unannotated and promptCaching is enabled`() {
        val setting = ProviderSetting.Claude(
            apiKey = "test",
            promptCaching = true,
            promptCacheTtl = ClaudePromptCacheTtl.ONE_HOUR,
        )
        val messages = listOf(
            UIMessage.system("first system"),
            UIMessage.user("hello"),
            UIMessage.system("second system"),
        )

        val request = provider.buildMessageRequest(setting, messages, params)
        val systemBlocks = request.getValue("system").jsonArray
        assertEquals(2, systemBlocks.size)
        assertFalse(systemBlocks[0].jsonObject.containsKey("cache_control"))

        val cacheControl = systemBlocks[1].jsonObject.getValue("cache_control").jsonObject
        assertEquals("ephemeral", cacheControl.getValue("type").jsonPrimitive.content)
        assertEquals("1h", cacheControl.getValue("ttl").jsonPrimitive.content)
    }

    @Test
    fun `places cache_control on last SYSTEM_PROMPT_STABLE_SECTION part when promptCaching is enabled`() {
        val setting = ProviderSetting.Claude(
            apiKey = "test",
            promptCaching = true,
            promptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(
                    UIMessagePart.Text(
                        text = "stable part 1",
                        metadata = sectionMetadata(SYSTEM_PROMPT_STABLE_SECTION),
                    ),
                    UIMessagePart.Text(
                        text = "stable part 2",
                        metadata = sectionMetadata(SYSTEM_PROMPT_STABLE_SECTION),
                    ),
                    UIMessagePart.Text(
                        text = "dynamic summary",
                        metadata = sectionMetadata(SYSTEM_PROMPT_DYNAMIC_SECTION),
                    ),
                ),
            ),
            UIMessage.user("hello"),
            UIMessage.system("runtime trailing system"),
        )

        val request = provider.buildMessageRequest(setting, messages, params)
        val systemBlocks = request.getValue("system").jsonArray
        assertEquals(4, systemBlocks.size)
        assertFalse(systemBlocks[0].jsonObject.containsKey("cache_control"))

        val stableCacheControl = systemBlocks[1].jsonObject.getValue("cache_control").jsonObject
        assertEquals("ephemeral", stableCacheControl.getValue("type").jsonPrimitive.content)
        assertFalse(stableCacheControl.containsKey("ttl"))

        assertFalse(systemBlocks[2].jsonObject.containsKey("cache_control"))
        assertFalse(systemBlocks[3].jsonObject.containsKey("cache_control"))
    }

    @Test
    fun `omits cache_control even on stable sections when promptCaching is disabled`() {
        val setting = ProviderSetting.Claude(apiKey = "test", promptCaching = false)
        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(
                    UIMessagePart.Text(
                        text = "stable part",
                        metadata = sectionMetadata(SYSTEM_PROMPT_STABLE_SECTION),
                    ),
                ),
            ),
            UIMessage.user("hello"),
        )

        val request = provider.buildMessageRequest(setting, messages, params)
        val systemBlocks = request.getValue("system").jsonArray
        assertEquals(1, systemBlocks.size)
        assertFalse(systemBlocks[0].jsonObject.containsKey("cache_control"))
    }

    private fun sectionMetadata(section: String) = buildJsonObject {
        put(SYSTEM_PROMPT_SECTION_METADATA_KEY, section)
    }
}
