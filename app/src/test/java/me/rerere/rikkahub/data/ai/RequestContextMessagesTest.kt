package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestContextMessagesTest {
    @Test
    fun `runtime instructions stay after history without changing the user turn`() {
        val user = UIMessage.user("Check the weather")
        val toolResult = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(
                toolCallId = "weather-1",
                toolName = "weather",
                input = "{}",
                output = listOf(UIMessagePart.Text("Sunny")),
            )),
        )
        val firstStep = listOf(user)
        val nextStep = firstStep + toolResult

        val firstRequest = firstStep.withRuntimeContextInstruction("state=ENDED")
        val nextRequest = nextStep.withRuntimeContextInstruction("state=INACTIVE")
        assertEquals(firstStep, firstRequest.dropLast(1))
        assertEquals(nextStep, nextRequest.dropLast(1))
        assertEquals(MessageRole.SYSTEM, firstRequest.last().role)
        assertEquals("state=ENDED", firstRequest.last().toText())
        assertEquals("state=INACTIVE", nextRequest.last().toText())
        assertEquals("Check the weather", user.toText())
        assertEquals(listOf(user, toolResult), nextStep)
        assertEquals(nextStep, nextStep.withRuntimeContextInstruction(null))
        assertEquals(nextStep, nextStep.withRuntimeContextInstruction(""))
    }
}
