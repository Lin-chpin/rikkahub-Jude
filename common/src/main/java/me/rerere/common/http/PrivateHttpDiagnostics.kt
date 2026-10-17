package me.rerere.common.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Response
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class PrivateHttpFailure internal constructor(val statusCode: Int, val diagnosticDetail: String) :
    Exception("Private request failed: $diagnosticDetail")

fun privateFailureDiagnosticDetail(error: Throwable): String? = privateHttpFailureCause(error)?.diagnosticDetail

fun privateFailureStatusCode(error: Throwable): Int? = privateHttpFailureCause(error)?.statusCode

private fun privateHttpFailureCause(error: Throwable): PrivateHttpFailure? {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        if (current is PrivateHttpFailure) return current
        current = current.cause
    }
    return null
}

/** Only protocol fields with known meanings survive; free-form messages may echo private input. */
fun privateHttpFailureDetails(response: Response, nowMillis: Long = System.currentTimeMillis()): String = buildList {
    add("http_status=${response.code}")
    if (response.isSuccessful) return@buildList
    val retryAfter = response.header("Retry-After")
    val retrySeconds = retryAfter?.nonNegativeNumber() ?: retryAfter?.let {
        runCatching {
            ((ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
                .coerceAtLeast(0) / 1000.0).toString()
        }.getOrNull()
    }
    retrySeconds?.let { add("retry_after_seconds=$it") }
    listOf("limit-requests", "remaining-requests", "limit-tokens", "remaining-tokens").forEach { field ->
        response.header("x-ratelimit-$field")?.toLongOrNull()?.takeIf { it >= 0 }?.let {
            add("${field.replace('-', '_')}=$it")
        }
    }
    val body = runCatching { response.peekBody(64 * 1024L).string() }.getOrNull()
    val root = body?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    if (root == null) {
        add("error_body=${if (body.isNullOrBlank()) "empty_or_unavailable" else "unrecognized"}")
        return@buildList
    }
    val error = root["error"] as? JsonObject ?: root
    listOf("status", "code", "type").forEach { field ->
        error.text(field)?.let { value ->
            val normalized = value.uppercase(Locale.ROOT)
            val safe = normalized.takeIf { it in SAFE_ERROR_CODES }
                ?: value.toIntOrNull()?.takeIf { it in 100..599 }?.toString()
                ?: "unrecognized"
            add("provider_$field=$safe")
        }
    }
    val details = (error["details"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    details.filter { it.text("@type") == "type.googleapis.com/google.rpc.ErrorInfo" }.forEach {
        it.text("reason")?.uppercase(Locale.ROOT)?.takeIf { reason -> reason in SAFE_ERROR_CODES }
            ?.let { reason -> add("provider_reason=$reason") }
    }
    details.filter { it.text("@type") == "type.googleapis.com/google.rpc.RetryInfo" }.forEach {
        it.text("retryDelay")?.takeIf { delay -> delay.endsWith('s') }
            ?.dropLast(1)?.nonNegativeNumber()?.let { delay -> add("provider_retry_seconds=$delay") }
    }
    val quotas = details.filter { it.text("@type") == "type.googleapis.com/google.rpc.QuotaFailure" }
        .flatMap { (it["violations"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
        .map { violation ->
            val metric = listOfNotNull(violation.text("quotaId"), violation.text("quotaMetric"))
                .joinToString("").lowercase(Locale.ROOT).filter(Char::isLetter)
            val resource = when {
                "inputtoken" in metric -> "input_tokens"
                "outputtoken" in metric -> "output_tokens"
                "token" in metric -> "tokens"
                "request" in metric -> "requests"
                else -> "unknown_resource"
            }
            val window = when {
                "perminute" in metric -> "per_minute"
                "perday" in metric -> "per_day"
                "perhour" in metric -> "per_hour"
                else -> "unknown_window"
            }
            "${resource}_${window}" + if ("freetier" in metric) "_free_tier" else ""
        }.distinct()
    if (quotas.isNotEmpty()) add("quota=${quotas.joinToString(",")}")
}.joinToString(" ")

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun String.nonNegativeNumber(): String? = takeIf { length <= 64 }
    ?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.toString()

private val SAFE_ERROR_CODES = setOf(
    "RESOURCE_EXHAUSTED", "RATE_LIMIT_EXCEEDED", "RATE_LIMIT_ERROR", "RATE_LIMITED", "TOO_MANY_REQUESTS",
    "QUOTA_EXCEEDED", "INSUFFICIENT_QUOTA", "INSUFFICIENT_BALANCE", "BILLING_HARD_LIMIT_REACHED",
    "RPM_LIMIT_EXCEEDED", "TPM_LIMIT_EXCEEDED", "RPD_LIMIT_EXCEEDED", "TOKEN_LIMIT_EXCEEDED",
    "OVERLOADED_ERROR", "SERVER_ERROR", "API_ERROR", "INTERNAL", "INTERNAL_ERROR", "UNAVAILABLE",
    "DEADLINE_EXCEEDED", "PERMISSION_DENIED", "UNAUTHENTICATED", "AUTHENTICATION_ERROR", "INVALID_API_KEY",
    "INVALID_ARGUMENT", "INVALID_REQUEST_ERROR", "FAILED_PRECONDITION", "NOT_FOUND", "NOT_FOUND_ERROR",
    "INSUFFICIENT_USER_QUOTA", "INSUFFICIENT_CHANNEL_QUOTA", "NEW_API_ERROR", "ONE_API_ERROR", "NO_AVAILABLE_CHANNEL",
)
