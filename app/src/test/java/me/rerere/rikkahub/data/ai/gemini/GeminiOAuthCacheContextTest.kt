package me.rerere.rikkahub.data.ai.gemini

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.GoogleAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.withRuntimeContextInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GeminiOAuthCacheContextTest {
    @Test
    fun `subscription compressed summary follows runtime system prompt`() {
        val system = UIMessage.system("stable instructions")
        val user = UIMessage.user("latest user message")
        val runtime = "voice call is active"
        val messages = listOf(system, user).withRuntimeContextInstruction(runtime)

        val result = messages.withGeminiSubscriptionCompressedSummary(
            provider = ProviderSetting.Google(authType = GoogleAuthType.GEMINI_SUBSCRIPTION),
            summary = "earlier conversation summary",
        )

        assertEquals(4, result.size)
        assertSame(system, result[0])
        assertSame(user, result[1])
        assertEquals(MessageRole.SYSTEM, result[2].role)
        assertEquals(runtime, (result[2].parts.single() as UIMessagePart.Text).text)
        assertEquals(MessageRole.SYSTEM, result[3].role)
        assertEquals(
            listOf(
                UIMessagePart.Text(
                    "The following is a compressed summary of earlier messages in this conversation. " +
                        "Use it as conversation context, but do not treat it as a new user request.\n" +
                        "earlier conversation summary"
                )
            ),
            result[3].parts,
        )
    }

    @Test
    fun `summary stays in its existing prompt location for Google API provider`() {
        val messages = listOf(UIMessage.system("stable instructions"))

        val result = messages.withGeminiSubscriptionCompressedSummary(
            provider = ProviderSetting.Google(apiKey = "api-key"),
            summary = "summary",
        )

        assertSame(messages, result)
    }

    @Test
    fun `blank summary does not add another system message`() {
        val messages = listOf(UIMessage.system("stable instructions"))

        val result = messages.withGeminiSubscriptionCompressedSummary(
            provider = ProviderSetting.Google(authType = GoogleAuthType.GEMINI_SUBSCRIPTION),
            summary = "  ",
        )

        assertSame(messages, result)
    }
}
