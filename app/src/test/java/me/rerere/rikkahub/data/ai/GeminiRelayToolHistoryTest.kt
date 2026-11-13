package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiRelayToolHistoryTest {
    @Test
    fun `only unofficial target Gemini route projects tool history as readable request text`() {
        val model = Model(modelId = "gemini-3.8-flash-thinking")
        val openAiRelay = ProviderSetting.OpenAI()
        val openAiGoogleEndpoint = ProviderSetting.OpenAI(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        )
        val officialGoogle = ProviderSetting.Google()
        val googleRelay = ProviderSetting.Google(baseUrl = "https://relay.example/v1beta")
        val vertexGoogle = ProviderSetting.Google(vertexAI = true)

        assertTrue(shouldProjectGeminiRelayToolHistory(model, openAiRelay))
        assertTrue(shouldProjectGeminiRelayToolHistory(model, googleRelay))
        assertFalse(shouldProjectGeminiRelayToolHistory(model, officialGoogle))
        assertFalse(shouldProjectGeminiRelayToolHistory(model, vertexGoogle))
        assertFalse(shouldProjectGeminiRelayToolHistory(model, openAiGoogleEndpoint))
        assertTrue(shouldProjectGeminiRelayToolHistory(Model(modelId = "gemini-2.5-pro"), openAiRelay))
        assertTrue(shouldProjectGeminiRelayToolHistory(Model(modelId = "gemini-9.0-flash-preview"), openAiRelay))
        assertFalse(shouldProjectGeminiRelayToolHistory(Model(modelId = "gpt-6"), openAiRelay))

        val stored = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Searching now."),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "web_search",
                    input = "{\"query\":\"example\"}",
                    output = listOf(
                        UIMessagePart.Text("Found https://example.com"),
                        UIMessagePart.Image("data:image/png;base64,AAAA"),
                    ),
                ),
            ),
        )
        val request = listOf(stored).withToolHistoryAsText().single()

        assertTrue(stored.parts.any { it is UIMessagePart.Tool })
        assertFalse(request.parts.any { it is UIMessagePart.Tool })
        assertEquals(
            "Searching now.\n[Historical tool call: web_search; id=call-1]\n" +
                "Arguments: {\"query\":\"example\"}\nResult:\n" +
                "Found https://example.com\n" +
                "[Image omitted from relay context; source=embedded media not forwarded]",
            request.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
        )

        val importedToolMessage = UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(UIMessagePart.Text("Found https://example.com")),
        )
        val projectedToolMessage = listOf(importedToolMessage).withToolHistoryAsText().single()
        assertEquals(MessageRole.ASSISTANT, projectedToolMessage.role)
        assertEquals(
            "[Historical tool response]\nFound https://example.com",
            projectedToolMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
        )
    }
}
