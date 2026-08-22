package me.rerere.rikkahub.data.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.tts.provider.TTSProviderSetting

internal const val CHAT_VOICE_REPLY_ERROR_PREFIX = "rikkahub.chat_voice_reply.error:"

enum class ChatVoiceReplyErrorCode {
    NO_MODEL,
    MISSING_API_KEY,
    MISSING_VOICE,
    MISSING_ENDPOINT,
    AUTHENTICATION,
    BALANCE,
    RATE_LIMIT,
    TIMEOUT,
    NETWORK,
    SYSTEM_ENGINE,
    INVALID_REQUEST,
    MODEL_UNAVAILABLE,
    EMPTY_AUDIO,
    TOOL_FAILURE,
    UNKNOWN,
}

data class ChatVoiceReplyError(
    val code: ChatVoiceReplyErrorCode,
    val message: String? = null,
)

fun ChatVoiceReplyError.userMessage(): String = message ?: when (code) {
    ChatVoiceReplyErrorCode.NO_MODEL -> "未配置语音模型，请先选择并配置 TTS Provider 和模型。"
    ChatVoiceReplyErrorCode.MISSING_API_KEY -> "未配置语音模型 API Key，请先填写 API Key。"
    ChatVoiceReplyErrorCode.MISSING_VOICE -> "未配置语音音色，请先选择音色或 Voice ID。"
    ChatVoiceReplyErrorCode.MISSING_ENDPOINT -> "未配置语音服务地址，请检查 Base URL。"
    ChatVoiceReplyErrorCode.AUTHENTICATION -> "语音模型鉴权失败，API Key 无效或已过期。"
    ChatVoiceReplyErrorCode.BALANCE -> "语音模型余额不足或额度已用尽，请充值或更换 Provider。"
    ChatVoiceReplyErrorCode.RATE_LIMIT -> "语音模型触发限流（HTTP 429），请稍后重试。"
    ChatVoiceReplyErrorCode.TIMEOUT -> "语音服务连接超时，请检查网络或服务地址。"
    ChatVoiceReplyErrorCode.NETWORK -> "无法连接语音服务，请检查网络、VPN 或 Base URL。"
    ChatVoiceReplyErrorCode.SYSTEM_ENGINE -> "系统语音引擎不可用，请安装或启用系统 TTS 引擎。"
    ChatVoiceReplyErrorCode.INVALID_REQUEST -> "语音服务拒绝了请求，请检查模型、音色和文本参数。"
    ChatVoiceReplyErrorCode.MODEL_UNAVAILABLE -> "语音模型不存在或暂不可用，请更换模型。"
    ChatVoiceReplyErrorCode.EMPTY_AUDIO -> "语音服务返回了空音频，当前模型可能不支持这段文本。"
    ChatVoiceReplyErrorCode.TOOL_FAILURE -> "语音工具调用失败，已降级为普通文本。"
    ChatVoiceReplyErrorCode.UNKNOWN -> "语音生成失败，服务端没有返回有效音频。"
}

internal fun encodeChatVoiceReplyError(error: ChatVoiceReplyError): String =
    buildString {
        append(CHAT_VOICE_REPLY_ERROR_PREFIX)
        append(error.code.name)
        error.message
            ?.replace('|', '/')
            ?.replace('\n', ' ')
            ?.takeIf { it.isNotBlank() }
            ?.let { append('|').append(it) }
    }

fun UIMessagePart.Tool.chatVoiceReplyError(): ChatVoiceReplyError? {
    if (toolName != CHAT_VOICE_REPLY_TOOL_NAME || !isExecuted) return null

    val outputText = output
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (outputText.startsWith(CHAT_VOICE_REPLY_ERROR_PREFIX)) {
        val payload = outputText.removePrefix(CHAT_VOICE_REPLY_ERROR_PREFIX)
        val code = payload
            .substringBefore('|')
            .lineSequence()
            .firstOrNull()
            ?.let { value -> runCatching { ChatVoiceReplyErrorCode.valueOf(value) }.getOrNull() }
        val message = payload.substringAfter('|', "").takeIf { it.isNotBlank() }
        if (code != null) return ChatVoiceReplyError(code, message)
    }

    val toolError = runCatching {
        (JsonInstant.parseToJsonElement(outputText) as? JsonObject)
            ?.get("error")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()
    return if (toolError.isNullOrBlank()) {
        null
    } else {
        classifyChatVoiceReplyErrorText(toolError)
    }
}

internal fun TTSProviderSetting.configurationError(): ChatVoiceReplyError? {
    fun remoteConfigurationError(
        apiKey: String,
        baseUrl: String,
        model: String? = null,
        voice: String? = null,
    ): ChatVoiceReplyError? {
        if (baseUrl.isBlank()) return ChatVoiceReplyError(ChatVoiceReplyErrorCode.MISSING_ENDPOINT)
        if (apiKey.isBlank()) return ChatVoiceReplyError(ChatVoiceReplyErrorCode.MISSING_API_KEY)
        if (model != null && model.isBlank()) return ChatVoiceReplyError(ChatVoiceReplyErrorCode.NO_MODEL)
        if (voice != null && voice.isBlank()) return ChatVoiceReplyError(ChatVoiceReplyErrorCode.MISSING_VOICE)
        return null
    }

    return when (this) {
        is TTSProviderSetting.SystemTTS -> null
        is TTSProviderSetting.OpenAI -> remoteConfigurationError(apiKey, baseUrl, model, voice)
        is TTSProviderSetting.Gemini -> remoteConfigurationError(apiKey, baseUrl, model, voiceName)
        is TTSProviderSetting.MiniMax -> remoteConfigurationError(apiKey, baseUrl, model, voiceId)
        is TTSProviderSetting.Qwen -> remoteConfigurationError(apiKey, baseUrl, model, voice)
        is TTSProviderSetting.Groq -> remoteConfigurationError(apiKey, baseUrl, model, voice)
        is TTSProviderSetting.XAI -> remoteConfigurationError(apiKey, baseUrl, voice = voiceId)
        is TTSProviderSetting.ElevenLabs -> remoteConfigurationError(apiKey, baseUrl, model, voiceId)
        is TTSProviderSetting.MiMo -> remoteConfigurationError(apiKey, baseUrl, model, voice)
    }
}

internal fun classifyChatVoiceReplyError(error: Throwable): ChatVoiceReplyError =
    classifyChatVoiceReplyErrorText(
        generateSequence(error) { it.cause }
            .take(4)
            .joinToString(" ") { it.message.orEmpty() },
    )

internal fun classifyChatVoiceReplyErrorText(rawMessage: String): ChatVoiceReplyError {
    val message = rawMessage.lowercase()
    val statusCode = Regex("\\b([45]\\d{2})\\b").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val code = when {
        listOf("no speakable voice text").any(message::contains) ->
            ChatVoiceReplyErrorCode.EMPTY_AUDIO
        listOf("api key is required", "api key required", "missing api key", "api key is empty").any(message::contains) ->
            ChatVoiceReplyErrorCode.MISSING_API_KEY
        listOf("texttospeech", "text to speech", "tts engine", "initialize texttospeech", "tts synthesis failed").any(message::contains) &&
            listOf("initialize", "engine", "failed to start", "not available").any(message::contains) ->
            ChatVoiceReplyErrorCode.SYSTEM_ENGINE
        statusCode == 401 || statusCode == 403 || listOf("unauthorized", "forbidden", "invalid api key", "invalid key", "authentication").any(message::contains) ->
            ChatVoiceReplyErrorCode.AUTHENTICATION
        statusCode == 402 || listOf("balance", "credit", "insufficient", "billing", "quota", "余额", "额度").any(message::contains) ->
            ChatVoiceReplyErrorCode.BALANCE
        statusCode == 429 || listOf("too many requests", "rate limit", "rate-limit", "请求过于频繁").any(message::contains) ->
            ChatVoiceReplyErrorCode.RATE_LIMIT
        statusCode == 408 || listOf("timeout", "timed out").any(message::contains) ->
            ChatVoiceReplyErrorCode.TIMEOUT
        statusCode == 404 || listOf("model not found", "model does not exist", "unknown model", "model unavailable").any(message::contains) ->
            ChatVoiceReplyErrorCode.MODEL_UNAVAILABLE
        statusCode == 400 || listOf("bad request", "invalid request", "invalid parameter", "unsupported model", "unsupported voice").any(message::contains) ->
            ChatVoiceReplyErrorCode.INVALID_REQUEST
        listOf("unknownhost", "connectexception", "connection", "network", "ssl", "dns").any(message::contains) ->
            ChatVoiceReplyErrorCode.NETWORK
        else -> ChatVoiceReplyErrorCode.UNKNOWN
    }
    return ChatVoiceReplyError(code, unknownErrorMessage(code, statusCode))
}

private fun unknownErrorMessage(code: ChatVoiceReplyErrorCode, statusCode: Int?): String? {
    if (code != ChatVoiceReplyErrorCode.UNKNOWN) return null
    return if (statusCode != null) {
        "语音服务返回 HTTP $statusCode 错误，请检查 Provider 配置或服务状态。"
    } else {
        null
    }
}
