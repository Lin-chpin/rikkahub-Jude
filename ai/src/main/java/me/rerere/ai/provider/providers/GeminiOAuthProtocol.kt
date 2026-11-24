package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.security.MessageDigest

const val GEMINI_CODE_ASSIST_ENDPOINT = "https://cloudcode-pa.googleapis.com"
const val GEMINI_DAILY_CODE_ASSIST_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com"
const val GEMINI_DAILY_SANDBOX_CODE_ASSIST_ENDPOINT =
    "https://daily-cloudcode-pa.sandbox.googleapis.com"

// Cloud Code Assist gates available models on the Antigravity client version in User-Agent.
const val GEMINI_ANTIGRAVITY_USER_AGENT =
    "antigravity/hub/2.33.0 (aidev_client; os_type=darwin; arch=arm64; cl=1023160026)"

val GEMINI_GENERATE_ENDPOINTS = listOf(
    GEMINI_DAILY_CODE_ASSIST_ENDPOINT,
    GEMINI_DAILY_SANDBOX_CODE_ASSIST_ENDPOINT,
    GEMINI_CODE_ASSIST_ENDPOINT,
)

fun Request.Builder.withGeminiAntigravityHeaders(accessToken: String): Request.Builder =
    header("Authorization", "Bearer $accessToken")
        .header("User-Agent", GEMINI_ANTIGRAVITY_USER_AGENT)

fun geminiClientMetadata(): JsonObject = buildJsonObject {
    put("ideType", "ANTIGRAVITY")
    put("platform", "PLATFORM_UNSPECIFIED")
    put("pluginType", "GEMINI")
}

internal fun deriveAntigravitySessionId(accountProjectId: String, conversationId: String?): String? {
    val projectId = accountProjectId.trim().takeIf(String::isNotEmpty) ?: return null
    val stableConversationId = conversationId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val seed = "rikkahub:antigravity:derived-session\u0000$projectId\u0000$stableConversationId"
    val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
    var value = 0L
    for (index in 0 until Long.SIZE_BYTES) {
        value = (value shl 8) or (digest[index].toLong() and 0xff)
    }
    return "-${value and Long.MAX_VALUE}"
}

internal fun JsonObject.withAntigravitySessionId(
    accountProjectId: String,
    conversationId: String?,
): JsonObject {
    val sessionId = deriveAntigravitySessionId(accountProjectId, conversationId) ?: return this
    // Keep the application conversation ID private and stable across turns in this account.
    return JsonObject(this + ("sessionId" to JsonPrimitive(sessionId)))
}
