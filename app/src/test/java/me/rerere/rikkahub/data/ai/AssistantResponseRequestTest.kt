package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AssistantResponseRequestTest {
    @Test
    fun `interruption retains received chunks and never retries or falls back`() = runBlocking {
        val failure = IOException("SSE stream closed before [DONE]")
        val provider = FakeProvider(flow {
            emit(chunk(UIMessagePart.Text("已收到的正文")))
            throw failure
        })
        val received = mutableListOf<MessageChunk>()
        val result = runCatching { request(provider, received::add) }

        assertSame(failure, result.exceptionOrNull())
        assertEquals("已收到的正文", received.single().choices.single().delta!!.toText())
        assertEquals(1, provider.streamRequests)
        assertEquals(0, provider.nonStreamRequests)
    }

    @Test
    fun `failure before output is not retried`() = runBlocking {
        val provider = FakeProvider(flow { throw IOException("connection reset") })
        assertTrue(runCatching { request(provider) }.exceptionOrNull() is IOException)
        assertEquals(1, provider.streamRequests)
        assertEquals(0, provider.nonStreamRequests)
    }

    @Test
    fun `empty stream reports empty reply without nonstream fallback`() = runBlocking {
        val provider = FakeProvider(flow { emit(MessageChunk("id", "test", emptyList())) })
        assertTrue(runCatching { request(provider) }.exceptionOrNull() is EmptyAssistantResponseException)
        assertEquals(1, provider.streamRequests)
        assertEquals(0, provider.nonStreamRequests)
    }

    @Test
    fun `reasoning and tool output do not count as empty responses`() = runBlocking {
        for (part in listOf(
            UIMessagePart.Reasoning("思考内容"),
            UIMessagePart.Tool("call", "get_time_info", "{}"),
        )) {
            val provider = FakeProvider(flow { emit(chunk(part)) })
            request(provider)
            assertEquals(1, provider.streamRequests)
            assertEquals(0, provider.nonStreamRequests)
        }
    }

    @Test
    fun `cancellation is propagated unchanged`() = runBlocking {
        val cancellation = CancellationException("stopped")
        val provider = FakeProvider(flow { throw cancellation })
        assertSame(cancellation, runCatching { request(provider) }.exceptionOrNull())
        assertEquals(1, provider.streamRequests)
        assertEquals(0, provider.nonStreamRequests)
    }

    private suspend fun request(provider: FakeProvider, onChunk: suspend (MessageChunk) -> Unit = {}) {
        requestAssistantResponse(
            provider, ProviderSetting.OpenAI(apiKey = "test"), listOf(UIMessage.user("test")),
            TextGenerationParams(Model(modelId = "test")), stream = true, onChunk = onChunk,
        )
    }

    private fun chunk(part: UIMessagePart) = MessageChunk(
        "id", "test", listOf(UIMessageChoice(
            0, UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)), null, null,
        )),
    )

    private class FakeProvider(private val chunks: Flow<MessageChunk>) : Provider<ProviderSetting> {
        var streamRequests = 0
        var nonStreamRequests = 0
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult =
            error("No image request expected")
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk {
            nonStreamRequests++
            error("No fallback request expected")
        }
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> {
            streamRequests++
            return chunks
        }
    }
}
