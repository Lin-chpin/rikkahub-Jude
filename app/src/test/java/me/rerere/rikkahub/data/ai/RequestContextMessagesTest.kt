package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestContextMessagesTest {
    @Test
    fun `request parts stop when feature is disabled between generation steps`() = runBlocking {
        var enabled = true
        val parts = listOf(UIMessagePart.Image("data:image/jpeg;base64,ZmFrZQ=="))

        assertEquals(parts, parts.onlyWhile { enabled })
        enabled = false
        assertEquals(emptyList<UIMessagePart>(), parts.onlyWhile { enabled })
    }

    @Test
    fun `call frames stay request only and survive a tool continuation`() {
        val user = UIMessage.user("你能看见吗")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("工具结果")))
        val history = listOf(user, assistant)
        val image = UIMessagePart.Image("data:image/jpeg;base64,ZmFrZQ==")
        val request = history.withRequestMessageParts(listOf(image))
            .withRuntimeContextInstruction("state=ACTIVE")
        assertEquals(listOf(UIMessagePart.Text("你能看见吗")), user.parts)
        assertEquals(listOf(user, assistant), history)
        assertEquals(user.parts + image, request.first().parts)
        assertEquals(assistant, request[1])
        assertEquals(history, history.withRequestMessageParts(emptyList()))
    }

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
