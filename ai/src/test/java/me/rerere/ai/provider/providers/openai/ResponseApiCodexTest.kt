package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseApiCodexTest {
    @Test
    fun `Codex subscription always requests streaming`() {
        val body = ResponseAPI(okhttp3.OkHttpClient()).buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION),
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(Model(modelId = "gpt-5.6-sol")),
            stream = false,
        )

        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
    }

    @Test
    fun `streaming chunks are aggregated for non streaming generation`() = runBlocking {
        val usage = TokenUsage(promptTokens = 3, completionTokens = 2, totalTokens = 5)
        val result = collectStreamingTextGeneration(
            model = Model(modelId = "gpt-5.6-sol"),
            stream = flowOf(
                textChunk("resp_1", "gpt-5.6-sol", "Hello"),
                textChunk("resp_1", "gpt-5.6-sol", " world", finishReason = "completed"),
                MessageChunk("resp_1", "gpt-5.6-sol", emptyList(), usage),
            ),
        )

        assertEquals("resp_1", result.id)
        assertEquals("completed", result.choices.single().finishReason)
        assertEquals(usage, result.usage)
        assertEquals(
            "Hello world",
            result.choices.single().message?.parts?.filterIsInstance<UIMessagePart.Text>()?.single()?.text,
        )
    }

    private fun textChunk(
        id: String,
        model: String,
        text: String,
        finishReason: String? = null,
    ) = MessageChunk(
        id = id,
        model = model,
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(MessageRole.ASSISTANT, listOf(UIMessagePart.Text(text))),
                message = null,
                finishReason = finishReason,
            )
        ),
    )
}
