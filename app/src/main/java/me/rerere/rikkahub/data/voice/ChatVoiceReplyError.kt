package me.rerere.rikkahub.data.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.HttpException
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.provider.TTSProviderErrorCode
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSResponseParsingException
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.tts.provider.TTSProviderSetting

internal const val CHAT_VOICE_REPLY_ERROR_PREFIX = "rikkahub.chat_voice_reply.error:"

enum class ChatVoiceReplyErrorCode {
    NO_MODEL,
    MISSING_API_KEY,
    MISSING_VOICE,
    MISSING_ENDPOINT,
    AUTHENTICATION,
    PERMISSION,
    BALANCE,
    RATE_LIMIT,
    TIMEOUT,
    NETWORK,
    SYSTEM_ENGINE,
    INVALID_REQUEST,
    MODEL_UNAVAILABLE,
    RESOURCE_NOT_FOUND,
    EMPTY_AUDIO,
    EMPTY_TEXT,
    AUDIO_STORAGE,
    RESPONSE_PARSING,
    REQUEST_TOO_LARGE,
    SERVER_ERROR,
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
    ChatVoiceReplyErrorCode.AUTHENTICATION -> "语音模型鉴权失败，请检查 API Key 或账号登录状态。"
    ChatVoiceReplyErrorCode.PERMISSION -> "语音服务拒绝访问，请检查账号权限及语音功能权限。"
    ChatVoiceReplyErrorCode.BALANCE -> "语音服务要求检查账户付款状态或可用额度。"
    ChatVoiceReplyErrorCode.RATE_LIMIT -> "语音服务限制了请求频率或额度，请稍后重试并检查账户用量。"
    ChatVoiceReplyErrorCode.TIMEOUT -> "语音请求超时，请稍后重试。"
    ChatVoiceReplyErrorCode.NETWORK -> "无法连接语音服务，请检查网络、VPN 或 Base URL。"
    ChatVoiceReplyErrorCode.SYSTEM_ENGINE -> "系统语音引擎不可用，请安装或启用系统 TTS 引擎。"
    ChatVoiceReplyErrorCode.INVALID_REQUEST -> "语音服务拒绝了请求，请检查请求参数。"
    ChatVoiceReplyErrorCode.MODEL_UNAVAILABLE -> "所选语音模型暂不可用，请检查模型配置。"
    ChatVoiceReplyErrorCode.RESOURCE_NOT_FOUND -> "语音服务未找到请求的资源，请检查服务地址、模型或音色配置。"
    ChatVoiceReplyErrorCode.EMPTY_AUDIO -> "语音服务没有返回音频数据。"
    ChatVoiceReplyErrorCode.EMPTY_TEXT -> "处理后的文本中没有可朗读内容。"
    ChatVoiceReplyErrorCode.AUDIO_STORAGE -> "语音已生成，但保存到本地失败。"
    ChatVoiceReplyErrorCode.RESPONSE_PARSING -> "语音服务响应解析失败，请稍后重试。"
    ChatVoiceReplyErrorCode.REQUEST_TOO_LARGE -> "语音请求内容过大，请缩短文本后重试。"
    ChatVoiceReplyErrorCode.SERVER_ERROR -> "语音服务返回服务端错误，请稍后重试。"
    ChatVoiceReplyErrorCode.TOOL_FAILURE -> "语音工具调用失败，已降级为普通文本。"
    ChatVoiceReplyErrorCode.UNKNOWN -> "语音生成未完成，具体原因无法确认。"
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
        ChatVoiceReplyError(ChatVoiceReplyErrorCode.UNKNOWN)
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

internal fun classifyChatVoiceReplyError(error: Throwable): ChatVoiceReplyError {
    val causes = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
    causes.filterIsInstance<ChatVoiceReplyGenerationException>().firstOrNull()?.let {
        return ChatVoiceReplyError(it.errorCode)
    }
    if (causes.any { it is TTSResponseParsingException }) {
        return ChatVoiceReplyError(ChatVoiceReplyErrorCode.RESPONSE_PARSING)
    }
    val providerFailure = causes.filterIsInstance<TTSProviderException>().firstOrNull()
    (providerFailure?.providerCode ?: providerFailure?.providerType)
        ?.let(::classifyChatVoiceReplyProviderCode)
        ?.let { return ChatVoiceReplyError(it) }
    val statusCode = causes.filterIsInstance<HttpException>()
        .firstNotNullOfOrNull { it.statusCode }
        ?: providerFailure?.statusCode
    if (statusCode != null) return classifyChatVoiceReplyStatus(statusCode)
    if (causes.any { it is java.net.SocketTimeoutException }) {
        return ChatVoiceReplyError(ChatVoiceReplyErrorCode.TIMEOUT)
    }
    if (causes.any { it is java.net.UnknownHostException }) {
        return ChatVoiceReplyError(ChatVoiceReplyErrorCode.NETWORK, "无法解析语音服务域名，请检查服务地址和网络连接。")
    }
    if (causes.any { it is javax.net.ssl.SSLException }) {
        return ChatVoiceReplyError(ChatVoiceReplyErrorCode.NETWORK, "无法建立语音服务安全连接。")
    }
    if (causes.any { it is java.net.ConnectException || it is java.net.NoRouteToHostException }) {
        return ChatVoiceReplyError(ChatVoiceReplyErrorCode.NETWORK, "无法建立语音服务连接，请检查网络和服务地址。")
    }
    if (causes.any { it is java.net.SocketException }) {
        return ChatVoiceReplyError(ChatVoiceReplyErrorCode.NETWORK, "语音服务连接中断，具体阶段无法确认。")
    }
    return ChatVoiceReplyError(ChatVoiceReplyErrorCode.UNKNOWN)
}

private fun classifyChatVoiceReplyProviderCode(code: TTSProviderErrorCode): ChatVoiceReplyErrorCode? = when (code) {
    TTSProviderErrorCode.PAYMENT_ISSUE,
    TTSProviderErrorCode.PAYMENT_REQUIRED,
    TTSProviderErrorCode.INSUFFICIENT_QUOTA,
    TTSProviderErrorCode.BILLING_HARD_LIMIT_REACHED,
    TTSProviderErrorCode.QUOTA_EXCEEDED -> ChatVoiceReplyErrorCode.BALANCE
    TTSProviderErrorCode.RATE_LIMIT_EXCEEDED,
    TTSProviderErrorCode.RATE_LIMITED -> ChatVoiceReplyErrorCode.RATE_LIMIT
    TTSProviderErrorCode.INVALID_API_KEY,
    TTSProviderErrorCode.AUTHENTICATION_ERROR,
    TTSProviderErrorCode.AUTH_ERROR,
    TTSProviderErrorCode.UNAUTHORIZED -> ChatVoiceReplyErrorCode.AUTHENTICATION
    TTSProviderErrorCode.PERMISSION_DENIED,
    TTSProviderErrorCode.FORBIDDEN -> ChatVoiceReplyErrorCode.PERMISSION
    TTSProviderErrorCode.MODEL_NOT_FOUND,
    TTSProviderErrorCode.MODEL_UNAVAILABLE -> ChatVoiceReplyErrorCode.MODEL_UNAVAILABLE
    TTSProviderErrorCode.VOICE_NOT_FOUND -> ChatVoiceReplyErrorCode.MISSING_VOICE
    TTSProviderErrorCode.PAYLOAD_TOO_LARGE -> ChatVoiceReplyErrorCode.REQUEST_TOO_LARGE
    TTSProviderErrorCode.INVALID_REQUEST_ERROR,
    TTSProviderErrorCode.INVALID_REQUEST,
    TTSProviderErrorCode.INVALID_PARAMETER,
    TTSProviderErrorCode.BAD_REQUEST -> ChatVoiceReplyErrorCode.INVALID_REQUEST
}

private fun classifyChatVoiceReplyStatus(status: Int): ChatVoiceReplyError = when (status) {
    400 -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.INVALID_REQUEST)
    401 -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.AUTHENTICATION)
    402 -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.BALANCE)
    403 -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.PERMISSION)
    404 -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.RESOURCE_NOT_FOUND)
    408 -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.TIMEOUT)
    413 -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.REQUEST_TOO_LARGE)
    429 -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.RATE_LIMIT)
    in 500..599 -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.SERVER_ERROR)
    else -> ChatVoiceReplyError(ChatVoiceReplyErrorCode.UNKNOWN, "语音服务返回 HTTP $status 错误。")
}
