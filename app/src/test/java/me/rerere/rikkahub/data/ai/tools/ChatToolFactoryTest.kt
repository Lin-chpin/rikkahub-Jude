package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.voice.CHAT_VOICE_REPLY_TOOL_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolFactoryTest {
    @Test
    fun `active voice call preserves tool definitions and order while guarding disallowed tools`() = runBlocking {
        val imageTool = dummyTool(
            name = IMAGE_GENERATION_TOOL_NAME,
            systemPromptText = "image prompt",
            needsApproval = true,
        )
        val searchTool = dummyTool(name = "search_web")
        val timeTool = dummyTool(name = "get_time_info")
        val ttsTool = dummyTool(name = CHAT_VOICE_REPLY_TOOL_NAME)
        val requestCallTool = dummyTool(
            name = REQUEST_VOICE_CALL_TOOL_NAME,
            needsApproval = true,
        )
        val postMomentTool = dummyTool(name = "post_moment")
        val skillTool = dummyTool(name = "use_skill")
        val mcpTool = dummyTool(name = "mcp__fetch", needsApproval = true)

        val configuredLocalTools = listOf(timeTool, ttsTool, requestCallTool, postMomentTool)
        val normalTools = assembleChatTools(
            imageGenerationTool = imageTool,
            searchTools = listOf(searchTool),
            configuredLocalTools = configuredLocalTools,
            allowedLocalNames = configuredLocalTools.mapTo(mutableSetOf()) { it.name },
            skillTools = listOf(skillTool),
            mcpTools = listOf(mcpTool),
            voiceCallActive = false,
        )
        val voiceCallTools = assembleChatTools(
            imageGenerationTool = imageTool,
            searchTools = listOf(searchTool),
            configuredLocalTools = configuredLocalTools,
            allowedLocalNames = setOf("get_time_info"),
            skillTools = listOf(skillTool),
            mcpTools = listOf(mcpTool),
            voiceCallActive = true,
        )

        assertEquals(normalTools.map { it.name }, voiceCallTools.map { it.name })
        assertEquals(
            listOf(
                IMAGE_GENERATION_TOOL_NAME,
                "search_web",
                "get_time_info",
                CHAT_VOICE_REPLY_TOOL_NAME,
                REQUEST_VOICE_CALL_TOOL_NAME,
                "post_moment",
                "use_skill",
                "mcp__fetch",
            ),
            voiceCallTools.map { it.name },
        )

        val model = Model(modelId = "gpt-5")
        assertEquals(normalTools.map { it.description }, voiceCallTools.map { it.description })
        assertEquals(normalTools.map { it.parameters() }, voiceCallTools.map { it.parameters() })
        assertEquals(
            normalTools.map { it.systemPrompt(model, emptyList()) },
            voiceCallTools.map { it.systemPrompt(model, emptyList()) },
        )

        val guardedNames = setOf(
            IMAGE_GENERATION_TOOL_NAME,
            CHAT_VOICE_REPLY_TOOL_NAME,
            REQUEST_VOICE_CALL_TOOL_NAME,
            "post_moment",
        )
        voiceCallTools.forEach { tool ->
            if (tool.name in guardedNames) {
                assertFalse("${tool.name} should not require approval when disabled", tool.needsApproval)
                val failure = runCatching { tool.execute(JsonObject(emptyMap())) }.exceptionOrNull()
                assertTrue(
                    "${tool.name} should fail when executed during active voice call",
                    failure?.message?.contains("Tool unavailable during an active voice call") == true,
                )
            } else {
                val result = tool.execute(JsonObject(emptyMap()))
                assertEquals(listOf(UIMessagePart.Text("ok:${tool.name}")), result)
            }
        }
        assertTrue(voiceCallTools.single { it.name == "mcp__fetch" }.needsApproval)
    }

    private fun dummyTool(
        name: String,
        systemPromptText: String = "",
        needsApproval: Boolean = false,
    ): Tool = Tool(
        name = name,
        description = "desc:$name",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        systemPrompt = { _, _ -> systemPromptText },
        needsApproval = needsApproval,
        execute = { listOf(UIMessagePart.Text("ok:$name")) },
    )
}
