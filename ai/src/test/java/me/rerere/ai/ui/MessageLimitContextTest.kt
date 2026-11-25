package me.rerere.ai.ui

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MessageLimitContextTest {
    @Test
    fun `limitContextBySteps returns original list when limit is non-positive or size within limit`() {
        val messages = listOf(
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2"),
        )

        assertSame(messages, messages.limitContextBySteps(0))
        assertSame(messages, messages.limitContextBySteps(-1))
        assertSame(messages, messages.limitContextBySteps(3))
        assertSame(messages, messages.limitContextBySteps(5))
    }

    @Test
    fun `limitContextBySteps keeps cut start stable across appends within a stride`() {
        // limit = 6 -> target = 3, stride = 3
        // For size in 7..8, startIndex = (((size - 6) / 3 + 1) * 3) = 3 -> aligns back to USER at index 2 ("u1").
        val base = listOf(
            UIMessage.user("u0"),
            UIMessage.assistant("a0"),
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2"),
            UIMessage.assistant("a2"),
            UIMessage.user("u3"),
        )
        val size7 = base.limitContextBySteps(6)
        val size8 = (base + UIMessage.assistant("a3")).limitContextBySteps(6)

        assertEquals("u1", size7.first().toText())
        assertEquals("u1", size8.first().toText())
        assertEquals(5, size7.size)
        assertEquals(6, size8.size)

        // Appending a trailing tool call at size = 8 within the same stride must not shift index 2.
        val size8WithTrailingTool = (base + assistantToolCall("t_tail"))
            .limitContextBySteps(6)
        assertEquals("u1", size8WithTrailingTool.first().toText())
        assertEquals(6, size8WithTrailingTool.size)

        // For size in 9..11, startIndex advances to 6 -> cuts at u3.
        val size9 = (base + UIMessage.assistant("a3") + UIMessage.user("u4")).limitContextBySteps(6)
        val size10 = (base + UIMessage.assistant("a3") + UIMessage.user("u4") + UIMessage.assistant("a4"))
            .limitContextBySteps(6)
        assertEquals("u3", size9.first().toText())
        assertEquals(3, size9.size)
        assertEquals("u3", size10.first().toText())
        assertEquals(4, size10.size)
    }

    @Test
    fun `limitContext and limitContextBySteps avoid cutting mid-tool execution`() {
        val messages = listOf(
            UIMessage.user("u0"),
            UIMessage.assistant("a0"),
            UIMessage.user("u1"),
            assistantToolCall("call_1"),
            assistantToolResult("call_1", "sunny"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2"),
            UIMessage.assistant("a2"),
        )

        // size = 4 -> raw startIndex = 4 (assistantToolResult("call_1"))
        // Must step back to call_1 (3) and then to u1 (2).
        val limited = messages.limitContext(4)
        assertEquals("u1", limited.first().toText())
        assertEquals(6, limited.size)

        // limit = 6 -> target = 3, stride = 3 -> raw startIndex = (((8 - 6) / 3 + 1) * 3) = 3 (assistantToolCall)
        // Must align back to u1 (2).
        val stepped = messages.limitContextBySteps(6)
        assertEquals("u1", stepped.first().toText())
        assertEquals(6, stepped.size)
    }

    @Test
    fun `limitContextBySteps skips tool-result-only USER wrapper when selecting turn boundary`() {
        val toolWrapper = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("   "),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "weather",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("sunny")),
                ),
            ),
        )
        val messages = listOf(
            UIMessage.user("u0"),
            assistantToolCall("call_1"),
            toolWrapper,
            UIMessage.assistant("a0"),
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
        )

        // limit = 4 -> target = 2, stride = 2 -> raw startIndex = (((6 - 4) / 2 + 1) * 2) = 4?
        // Wait: with size = 6, limit = 4 -> startIndex = 4 (u1). Add one more assistant message to hit startIndex = 3?
        // Actually for size = 5, limit = 4 -> startIndex = 2 (toolWrapper).
        val fiveMessages = messages.take(5)
        val steppedAtWrapper = fiveMessages.limitContextBySteps(4)
        assertEquals("u0", steppedAtWrapper.first().toText())
        assertEquals(5, steppedAtWrapper.size)

        // When startIndex = 3 (a0), indexOfLast USER in [0..3] would hit toolWrapper at 2, so it must skip to u0 (0).
        val sixMessagesWithLimit5 = listOf(
            UIMessage.user("u0"),
            assistantToolCall("call_1"),
            toolWrapper,
            UIMessage.assistant("a0"),
            UIMessage.assistant("a0_cont"),
            UIMessage.user("u1"),
        )
        // limit = 4 -> target = 2, stride = 2 -> size = 6 -> startIndex = 4 (a0_cont)
        val steppedFromAssistant = sixMessagesWithLimit5.limitContextBySteps(4)
        assertEquals("u0", steppedFromAssistant.first().toText())
        assertEquals(6, steppedFromAssistant.size)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `limitContextBySteps skips legacy ToolResult USER wrapper`() {
        val legacyWrapper = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.ToolResult(
                    toolCallId = "legacy_1",
                    toolName = "weather",
                    content = JsonPrimitive("sunny"),
                    arguments = JsonPrimitive("{}"),
                ),
            ),
        )
        val legacyCall = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ToolCall(
                    toolCallId = "legacy_1",
                    toolName = "weather",
                    arguments = "{}",
                ),
            ),
        )
        val messages = listOf(
            UIMessage.user("u0"),
            legacyCall,
            legacyWrapper,
            UIMessage.assistant("a0"),
            UIMessage.assistant("a1"),
            UIMessage.user("u1"),
        )

        // limit = 4 -> target = 2, stride = 2 -> size = 6 -> startIndex = 4 (a1)
        val stepped = messages.limitContextBySteps(4)
        assertEquals("u0", stepped.first().toText())
        assertEquals(6, stepped.size)
    }

    @Test
    fun `limitContextBySteps rejects USER message preceded by dangling tool state`() {
        val messages = listOf(
            UIMessage.user("u0"),
            assistantToolCall("dangling_1"),
            UIMessage.user("u1_after_dangling"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2_clean"),
            UIMessage.assistant("a2"),
        )

        // limit = 4 -> target = 2, stride = 2 -> size = 6 -> startIndex = 4? Wait:
        // For size = 5, limit = 4 -> startIndex = 2 (u1_after_dangling).
        // u1_after_dangling is preceded by dangling_1 in turn u0..dangling_1, so must step back to u0.
        val steppedAtDangling = messages.take(5).limitContextBySteps(4)
        assertEquals("u0", steppedAtDangling.first().toText())
        assertEquals(5, steppedAtDangling.size)

        // For size = 6, limit = 4 -> startIndex = 4 (u2_clean).
        // Previous turn starts at u1_after_dangling (2), and 2..3 has no dangling tool state, so u2_clean is valid.
        val steppedAtCleanTurn = messages.limitContextBySteps(4)
        assertEquals("u2_clean", steppedAtCleanTurn.first().toText())
        assertEquals(2, steppedAtCleanTurn.size)
    }

    @Test
    fun `limitContext stops executed-tool lookup at real USER turn boundary`() {
        val unifiedExecutedTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call_2",
                    toolName = "weather",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("rain")),
                ),
            ),
        )
        val messages = listOf(
            UIMessage.user("u0"),
            assistantToolCall("abandoned_call_1"),
            UIMessage.user("u1"),
            unifiedExecutedTool,
            UIMessage.assistant("a1"),
        )

        // size = 2 -> startIndex = 3 (unifiedExecutedTool with call_2).
        // Must NOT cross u1 (2) to grab abandoned_call_1 (1).
        val limited = messages.limitContext(2)
        assertEquals(2, limited.size)
        assertSame(unifiedExecutedTool, limited.first())
    }

    @Test
    fun `limitContextBySteps falls back to alignedStart when no valid USER exists`() {
        val messages = listOf(
            UIMessage.system("sys"),
            UIMessage.assistant("a0"),
            UIMessage.assistant("a1"),
            UIMessage.assistant("a2"),
            UIMessage.user("u1"),
        )

        // limit = 4 -> target = 2, stride = 2 -> size = 5 -> startIndex = 2 (a1), no USER in [0..2].
        val stepped = messages.limitContextBySteps(4)
        assertEquals("a1", stepped.first().toText())
        assertEquals(3, stepped.size)
    }

    private fun assistantToolCall(callId: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = callId,
                toolName = "weather",
                input = "{}",
                output = emptyList(),
            ),
        ),
    )

    private fun assistantToolResult(callId: String, result: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = callId,
                toolName = "weather",
                input = "{}",
                output = listOf(UIMessagePart.Text(result)),
            ),
        ),
    )
}
