package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `reasoning summary events produce one UI reasoning part`() = runBlocking {
        val result = collectStreamingTextGeneration(
            model = Model(modelId = "gpt-5.6-sol"),
            stream = flowOf(
                reasoningEvent("""
                    {"type":"response.reasoning_summary_part.added","item_id":"rs_1","part":{"type":"summary_text","text":""}}
                """),
                reasoningEvent("""
                    {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","delta":"draft"}
                """),
                reasoningEvent("""
                    {"type":"response.reasoning_summary_part.done","item_id":"rs_1","part":{"type":"summary_text","text":"final"}}
                """),
                textChunk("resp_1", "gpt-5.6-sol", "answer", finishReason = "completed"),
            ),
        )

        assertEquals("final", result.choices.single().message?.parts
            ?.filterIsInstance<UIMessagePart.Reasoning>()?.single()?.reasoning)
    }

    @Test
    fun `reasoning history is not replayed as request input`() {
        val input = ResponseAPI(okhttp3.OkHttpClient()).buildMessages(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning("old summary"),
                        UIMessagePart.Text("answer"),
                    ),
                )
            )
        )

        assertEquals(1, input.size)
        assertEquals("answer", input[0].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Codex keeps encrypted reasoning history and requests encrypted content`() {
        val body = ResponseAPI(okhttp3.OkHttpClient()).buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION),
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(
                            reasoning = "summary",
                            metadata = buildJsonObject {
                                put("reasoning_id", "rs_1")
                                put("encrypted_content", "enc_1")
                            },
                        ),
                        UIMessagePart.Text("answer"),
                    ),
                ),
            ),
            params = TextGenerationParams(
                Model(
                    modelId = "gpt-5.6-sol",
                    abilities = listOf(ModelAbility.REASONING),
                )
            ),
            stream = true,
        )

        assertEquals("reasoning.encrypted_content", body["include"]?.jsonArray?.single()?.jsonPrimitive?.content)
        val reasoning = body["input"]!!.jsonArray.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning"
        }.jsonObject
        assertEquals("enc_1", reasoning["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gpt 5 6 models are registered as reasoning models`() {
        listOf("gpt-5.6-Terra", "gpt-5.6-Luna", "gpt-5.6-Sol")
            .forEach { modelId ->
                assertEquals(
                    modelId,
                    listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                    ModelRegistry.MODEL_ABILITIES.getData(modelId),
                )
            }
        assertEquals(emptyList<ModelAbility>(), ModelRegistry.MODEL_ABILITIES.getData("gpt-5.6"))
        assertEquals(emptyList<ModelAbility>(), ModelRegistry.MODEL_ABILITIES.getData("gpt-5.6-mini"))
    }

    @Test
    fun `chat completions omits reasoning unless assistant has a tool call`() {
        val api = ChatCompletionsAPI(okhttp3.OkHttpClient(), KeyRoulette.default())
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("private"),
                UIMessagePart.Text("answer"),
            ),
        )
        val request = api.buildChatCompletionRequest(
            messages = listOf(assistant),
            params = TextGenerationParams(Model(modelId = "deepseek-chat")),
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com"),
        )
        assertNull(request["messages"]!!.jsonArray.single().jsonObject["reasoning_content"])

        val withTool = assistant.copy(
            parts = assistant.parts + UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "lookup",
                input = "{}",
            )
        )
        val toolRequest = api.buildChatCompletionRequest(
            messages = listOf(withTool),
            params = TextGenerationParams(Model(modelId = "deepseek-chat")),
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com"),
        )
        assertEquals(
            "private",
            toolRequest["messages"]!!.jsonArray.first {
                it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
            }.jsonObject["reasoning_content"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `dynamic system context is placed after the stable prefix`() {
        val body = ResponseAPI(okhttp3.OkHttpClient()).buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION),
            messages = listOf(
                UIMessage.system(
                    "stable instructions\n\n" +
                        "The following is a compressed summary of earlier messages in this conversation.\n" +
                        "dynamic context"
                ),
                UIMessage.user("current question"),
            ),
            params = TextGenerationParams(Model(modelId = "gpt-5.5")),
            stream = true,
        )

        assertEquals("stable instructions", body["instructions"]?.jsonPrimitive?.content)
        val input = body["input"]!!.jsonArray
        assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(
            "The following is a compressed summary of earlier messages in this conversation.\n" +
                "dynamic context",
            input[1].jsonObject["content"]?.jsonPrimitive?.content,
        )
        assertEquals("current question", input[0].jsonObject["content"]?.jsonPrimitive?.content)
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
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(text)),
                ),
                message = null,
                finishReason = finishReason,
            )
        ),
    )

    private fun reasoningEvent(json: String): MessageChunk =
        parseResponseReasoningEvent(Json.parseToJsonElement(json).jsonObject)!!
}
