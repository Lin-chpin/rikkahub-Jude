package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal class EmptyAssistantResponseException : IllegalStateException("模型接口没有返回可显示的回复，请手动重试。")

internal suspend fun requestAssistantResponse(
    provider: Provider<ProviderSetting>,
    providerSetting: ProviderSetting,
    messages: List<UIMessage>,
    params: TextGenerationParams,
    stream: Boolean,
    onChunk: suspend (MessageChunk) -> Unit,
) {
    if (!stream) {
        onChunk(provider.generateText(providerSetting, messages, params))
        return
    }

    var sawOutput = false
    // 请求可能已经在服务端执行；断流或空输出都交给用户决定是否重发。
    provider.streamText(providerSetting, messages, params).collect { chunk ->
        sawOutput = sawOutput || chunk.choices.any { choice ->
            val message = choice.delta ?: choice.message
            message?.role == MessageRole.ASSISTANT && message.parts.any { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text.isNotBlank()
                    is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
                    is UIMessagePart.Image -> part.url.isNotBlank()
                    is UIMessagePart.Video -> part.url.isNotBlank()
                    is UIMessagePart.Audio -> part.url.isNotBlank()
                    is UIMessagePart.Document -> part.url.isNotBlank()
                    else -> true
                }
            }
        }
        onChunk(chunk)
    }
    if (!sawOutput) throw EmptyAssistantResponseException()
}
