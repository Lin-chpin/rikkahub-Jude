package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponseMessagesTest {
    @Test
    fun `Google starts a separate assistant message after a completed tool step`() {
        val previous = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call_a",
                    toolName = "get_time_info",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result_a")),
                ),
            ),
        )
        val messages = listOf(UIMessage.user("question"), previous)

        val result = beginAssistantResponse(
            messages = messages,
            modelId = Uuid.random(),
            startNewMessage = false,
            providerSetting = ProviderSetting.Google(apiKey = "test"),
        )

        assertEquals(3, result.size)
        assertSame(previous, result[1])
        assertEquals(MessageRole.ASSISTANT, result.last().role)
        assertTrue(result.last().parts.isEmpty())
    }

    @Test
    fun `OpenAI keeps its existing assistant continuation behavior`() {
        val messages = listOf(
            UIMessage.user("question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call_a",
                        toolName = "get_time_info",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("result_a")),
                    ),
                ),
            ),
        )

        val result = beginAssistantResponse(
            messages = messages,
            modelId = Uuid.random(),
            startNewMessage = false,
            providerSetting = ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1"),
        )

        assertSame(messages, result)
    }

    @Test
    fun `Google waits for tool execution before opening the next response`() {
        val messages = listOf(
            UIMessage.user("question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call_a",
                        toolName = "get_time_info",
                        input = "{}",
                    ),
                ),
            ),
        )

        val result = beginAssistantResponse(
            messages = messages,
            modelId = Uuid.random(),
            startNewMessage = false,
            providerSetting = ProviderSetting.Google(apiKey = "test"),
        )

        assertSame(messages, result)
    }
}
