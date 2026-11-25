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
    fun `call frames and runtime user context stay request only and survive a tool continuation`() {
        val user = UIMessage.user("你能看见吗")
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(
                toolCallId = "call-1",
                toolName = "weather",
                input = "{}",
                output = listOf(UIMessagePart.Text("Sunny")),
            )),
        )
        val firstStep = listOf(user)
        val nextStep = listOf(user, assistant)
        val image = UIMessagePart.Image("data:image/jpeg;base64,ZmFrZQ==")
        val firstRequest = firstStep.withRequestMessageParts(listOf(image))
            .withRuntimeUserContext("state=ACTIVE")
        val nextRequest = nextStep.withRequestMessageParts(listOf(image))
            .withRuntimeUserContext("state=ACTIVE")

        assertEquals(listOf(UIMessagePart.Text("你能看见吗")), user.parts)
        assertEquals(listOf(user, assistant), nextStep)
        assertEquals(1, firstRequest.size)
        assertEquals(2, nextRequest.size)
        assertEquals(user.parts + image + UIMessagePart.Text("state=ACTIVE"), nextRequest.first().parts)
        assertEquals(firstRequest.first(), nextRequest.first())
        assertEquals(assistant, nextRequest[1])
        assertEquals(nextStep, nextStep.withRequestMessageParts(emptyList()))
        assertEquals(nextStep, nextStep.withRuntimeUserContext(null))
        assertEquals(nextStep, nextStep.withRuntimeUserContext("   "))
    }

    @Test
    fun `runtime user context falls back to system message when no user turn exists`() {
        val system = UIMessage.system("base")
        val request = listOf(system).withRuntimeUserContext("state=ACTIVE")
        assertEquals(2, request.size)
        assertEquals(MessageRole.SYSTEM, request.last().role)
        assertEquals("state=ACTIVE", request.last().toText())
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
