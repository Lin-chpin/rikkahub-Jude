package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentsHistoryTrimmingTest {
    @Test
    fun `history within limit is returned unchanged and non-positive limit returns empty`() {
        val messages = listOf(UIMessage.user("u1"), UIMessage.assistant("a1"))

        assertSame(messages, trimMomentsChatHistory(messages, maxMessages = 20))
        assertEquals(emptyList<UIMessage>(), trimMomentsChatHistory(messages, maxMessages = 0))
        assertEquals(emptyList<UIMessage>(), trimMomentsChatHistory(emptyList(), maxMessages = 20))
    }

    @Test
    fun `trimming aligns forward to a user turn without exceeding maxMessages`() {
        val messages = listOf(
            UIMessage.user("u0"),
            UIMessage.assistant("a0"),
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2"),
            UIMessage.assistant("a2"),
        )

        val trimmed = trimMomentsChatHistory(messages, maxMessages = 5)

        assertEquals(listOf("u1", "a1", "u2", "a2"), trimmed.map { it.toText() })
        assertTrue(trimmed.size <= 5)
        assertEquals(MessageRole.USER, trimmed.first().role)
    }

    @Test
    fun `trimming skips mid-tool messages and never cuts inside tool calls`() {
        val messages = listOf(
            UIMessage.user("u0"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.ToolCall(toolCallId = "call-1", toolName = "weather", arguments = "{}")),
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call-1",
                        toolName = "weather",
                        content = JsonPrimitive("Sunny"),
                        arguments = JsonPrimitive("{}"),
                    )
                ),
            ),
            UIMessage.assistant("a0"),
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
        )

        val trimmed = trimMomentsChatHistory(messages, maxMessages = 4)

        assertEquals(listOf("u1", "a1"), trimmed.map { it.toText() })
        assertEquals(MessageRole.USER, trimmed.first().role)
    }

    @Test
    fun `trimming avoids user turn that precedes a matching tool result from an earlier call`() {
        val messages = listOf(
            UIMessage.user("u0"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.ToolCall(toolCallId = "call-1", toolName = "weather", arguments = "{}")),
            ),
            UIMessage.user("u-interleaved"),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call-1",
                        toolName = "weather",
                        content = JsonPrimitive("Sunny"),
                        arguments = JsonPrimitive("{}"),
                    )
                ),
            ),
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
        )

        val trimmed = trimMomentsChatHistory(messages, maxMessages = 4)

        assertEquals(listOf("u1", "a1"), trimmed.map { it.toText() })
    }

    @Test
    fun `trimming returns empty when single tail turn exceeds maxMessages without safe user boundary`() {
        val messages = listOf(
            UIMessage.user("u0"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.ToolCall(toolCallId = "call-1", toolName = "t1", arguments = "{}")),
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call-1",
                        toolName = "t1",
                        content = JsonPrimitive("ok"),
                        arguments = JsonPrimitive("{}"),
                    )
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.ToolCall(toolCallId = "call-2", toolName = "t2", arguments = "{}")),
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call-2",
                        toolName = "t2",
                        content = JsonPrimitive("ok"),
                        arguments = JsonPrimitive("{}"),
                    )
                ),
            ),
            UIMessage.assistant("final"),
        )

        assertEquals(emptyList<UIMessage>(), trimMomentsChatHistory(messages, maxMessages = 4))
    }
}
