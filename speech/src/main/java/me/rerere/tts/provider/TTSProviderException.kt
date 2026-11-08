package me.rerere.tts.provider

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

enum class TTSProviderKind(val safeName: String) {
    OPENAI_COMPATIBLE("openai_compatible"),
    ELEVENLABS("elevenlabs"),
    GEMINI("gemini"),
    GROQ("groq"),
    QWEN("qwen"),
    XAI("xai"),
    MINIMAX("minimax"),
    MIMO("mimo"),
}

enum class TTSProviderErrorCode(val safeValue: String) {
    PAYMENT_ISSUE("payment_issue"),
    PAYMENT_REQUIRED("payment_required"),
    INSUFFICIENT_QUOTA("insufficient_quota"),
    BILLING_HARD_LIMIT_REACHED("billing_hard_limit_reached"),
    QUOTA_EXCEEDED("quota_exceeded"),
    RATE_LIMIT_EXCEEDED("rate_limit_exceeded"),
    RATE_LIMITED("rate_limited"),
    INVALID_API_KEY("invalid_api_key"),
    AUTHENTICATION_ERROR("authentication_error"),
    AUTH_ERROR("auth_error"),
    UNAUTHORIZED("unauthorized"),
    PERMISSION_DENIED("permission_denied"),
    FORBIDDEN("forbidden"),
    MODEL_NOT_FOUND("model_not_found"),
    MODEL_UNAVAILABLE("model_unavailable"),
    VOICE_NOT_FOUND("voice_not_found"),
    INVALID_REQUEST_ERROR("invalid_request_error"),
    INVALID_REQUEST("invalid_request"),
    INVALID_PARAMETER("invalid_parameter"),
    BAD_REQUEST("bad_request"),
    PAYLOAD_TOO_LARGE("payload_too_large");

    companion object {
        fun fromUntrusted(value: String?): TTSProviderErrorCode? {
            val normalized = value?.trim()?.lowercase()?.takeIf { it.length <= 64 } ?: return null
            return entries.firstOrNull { it.safeValue == normalized }
        }
    }
}

data class TTSProviderErrorDetails(
    val code: TTSProviderErrorCode? = null,
    val type: TTSProviderErrorCode? = null,
)

fun parseTTSProviderErrorDetails(body: String?): TTSProviderErrorDetails {
    val root = runCatching { Json.parseToJsonElement(body.orEmpty()).jsonObject }.getOrNull()
        ?: return TTSProviderErrorDetails()
    val nested = sequenceOf(root["error"], root["detail"])
        .mapNotNull { runCatching { it?.jsonObject }.getOrNull() }
        .firstOrNull()
    fun stringValue(value: JsonObject?, key: String): String? =
        (value?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun firstCode(value: JsonObject?, vararg keys: String): TTSProviderErrorCode? =
        keys.firstNotNullOfOrNull { key -> TTSProviderErrorCode.fromUntrusted(stringValue(value, key)) }
    return TTSProviderErrorDetails(
        code = firstCode(nested, "code", "status", "error_code")
            ?: firstCode(root, "code", "error_code"),
        type = firstCode(nested, "type", "error_type")
            ?: firstCode(root, "type", "error_type"),
    )
}

fun createTTSProviderHttpException(
    provider: TTSProviderKind,
    statusCode: Int,
    errorBody: String? = null,
): TTSProviderException {
    val details = parseTTSProviderErrorDetails(errorBody)
    return TTSProviderException(
        provider = provider,
        statusCode = statusCode,
        providerCode = details.code,
        providerType = details.type,
    )
}

fun createTTSProviderSseFailure(
    provider: TTSProviderKind,
    statusCode: Int?,
    cause: Throwable?,
    providerCode: String? = null,
    providerType: String? = null,
): Throwable {
    val code = TTSProviderErrorCode.fromUntrusted(providerCode)
    val type = TTSProviderErrorCode.fromUntrusted(providerType)
    return if (statusCode != null || code != null || type != null) {
        TTSProviderException(
            provider = provider,
            statusCode = statusCode,
            providerCode = code,
            providerType = type,
            cause = cause,
        )
    } else {
        cause ?: IOException("${provider.safeName} TTS stream failed; cause unknown")
    }
}

/** A provider returned a non-success HTTP response. Only safe structured details are retained. */
class TTSProviderException(
    val provider: TTSProviderKind,
    val statusCode: Int? = null,
    val providerCode: TTSProviderErrorCode? = null,
    val providerType: TTSProviderErrorCode? = null,
    cause: Throwable? = null,
) : IOException(
    buildString {
        append(provider.safeName).append(" TTS failure")
        statusCode?.let { append(" HTTP ").append(it) }
        providerCode?.let { append(" code=").append(it.safeValue) }
        providerType?.let { append(" type=").append(it.safeValue) }
    },
    cause,
)

class TTSResponseParsingException(
    val provider: TTSProviderKind,
    cause: Throwable? = null,
) : IOException("${provider.safeName} TTS response parsing failed", cause)
