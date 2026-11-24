package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatToolContinuationGroupingTest {
    @Test
    fun `groups consecutive tool-only assistant steps without changing source messages`() {
        val modelId = Uuid.random()
        val first = MessageNode.of(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(tool("call_a", "search_webcams", "first result")),
                modelId = modelId,
                usage = TokenUsage(
                    promptTokens = 100,
                    completionTokens = 20,
                    cachedTokens = 10,
                    cachedTokensReported = true,
                    totalTokens = 120,
                ),
            )
        )
        val second = MessageNode.of(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(tool("call_b", "weather_at", "second result")),
                modelId = modelId,
                usage = TokenUsage(
                    promptTokens = 120,
                    completionTokens = 25,
                    cachedTokens = 12,
                    cachedTokensReported = false,
                    totalTokens = 145,
                ),
            )
        )

        val display = listOf(first, second).groupAssistantToolContinuations()

        assertEquals(1, display.size)
        assertEquals(listOf(first, second), display.single().sourceNodes)
        assertFalse(display.single().showActions)
        assertEquals(first.id, display.single().node.id)
        assertEquals(listOf("call_a", "call_b"), display.single().node.currentMessage.getTools().map { it.toolCallId })
        assertEquals(220, display.single().node.currentMessage.usage?.promptTokens)
        assertEquals(45, display.single().node.currentMessage.usage?.completionTokens)
        assertEquals(22, display.single().node.currentMessage.usage?.cachedTokens)
        assertEquals(false, display.single().node.currentMessage.usage?.cachedTokensReported)
        assertEquals(listOf("call_a"), first.currentMessage.getTools().map { it.toolCallId })
    }

    @Test
    fun `keeps final assistant text and different model steps as separate display items`() {
        val firstModel = Uuid.random()
        val otherModel = Uuid.random()
        val first = MessageNode.of(toolMessage(firstModel, "call_a", "search_webcams"))
        val second = MessageNode.of(toolMessage(firstModel, "call_b", "weather_at"))
        val answer = MessageNode.of(UIMessage.assistant("Here is the result"))
        val otherModelCall = MessageNode.of(toolMessage(otherModel, "call_c", "nearby"))

        val display = listOf(first, second, answer, otherModelCall).groupAssistantToolContinuations()

        assertEquals(3, display.size)
        assertEquals(listOf(first, second), display[0].sourceNodes)
        assertTrue(display[1].showActions)
        assertSame(answer, display[1].node)
        assertSame(otherModelCall, display[2].node)
    }

    private fun tool(callId: String, name: String, result: String) = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = name,
        input = "{}",
        output = listOf(UIMessagePart.Text(result)),
    )

    private fun toolMessage(modelId: Uuid, callId: String, name: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(tool(callId, name, "result")),
        modelId = modelId,
    )
}
