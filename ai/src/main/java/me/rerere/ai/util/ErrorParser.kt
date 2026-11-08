package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Response

class HttpException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    val providerCode: String? = null,
    val providerType: String? = null,
) : RuntimeException(message, cause)

class ProviderResponseParseException(
    message: String,
    cause: Throwable,
    val safeContext: String? = null,
) : RuntimeException(message, cause)

internal fun Throwable?.withHttpStatus(response: Response?): Throwable? =
    if (response != null && !response.isSuccessful) {
        val parsed = findHttpException()
        HttpException(
            message = this?.message ?: "HTTP ${response.code}",
            statusCode = response.code,
            cause = this,
            providerCode = parsed?.providerCode?.safeProviderIdentifier(),
            providerType = parsed?.providerType?.safeProviderIdentifier(),
        )
    } else this

private fun Throwable?.findHttpException(): HttpException? {
    val seen = mutableSetOf<Throwable>()
    var current = this
    while (current != null && seen.add(current)) {
        if (current is HttpException) return current
        current = current.cause
    }
    return null
}

fun JsonElement.parseErrorDetail(): HttpException {
    val errorObject = (this as? JsonObject)?.get("error") as? JsonObject ?: this as? JsonObject
    val code = errorObject?.get("code")?.textOrNull()?.safeProviderIdentifier()
    return HttpException(
        message = "Provider reported an error",
        providerCode = code,
        providerType = errorObject?.get("type")?.textOrNull()?.safeProviderIdentifier(),
    )
}

fun Response.toHttpException(cause: Throwable? = null): HttpException {
    val parsed = runCatching { body?.string().orEmpty() }
        .mapCatching { body ->
            if (body.isBlank()) {
                HttpException("HTTP $code ${message.ifBlank { "request failed" }}")
            } else {
                Json.parseToJsonElement(body).parseErrorDetail()
            }
        }
        .getOrElse { error ->
            HttpException(
                "HTTP $code ${message.ifBlank { "request failed" }}",
                cause = ProviderResponseParseException(
                    "Provider error response could not be parsed",
                    error,
                ),
            )
        }
    return HttpException(
        message = parsed.message ?: "HTTP $code ${message.ifBlank { "request failed" }}",
        statusCode = code,
        cause = parsed.cause ?: cause,
        providerCode = parsed.providerCode,
        providerType = parsed.providerType,
    ).also { wrapped ->
        if (parsed.cause != null && cause != null && parsed.cause !== cause) {
            wrapped.addSuppressed(cause)
        }
    }
}

private fun JsonElement.textOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private val providerIdentifierPattern = Regex("[A-Za-z0-9_.-]{1,64}")
private val knownSafeProviderIdentifiers = setOf(
    "access_denied", "api_error", "auth_error", "authentication_error", "bad_request", "billing_hard_limit_reached",
    "blocklist", "configuration", "content_policy_violation", "context_length_exceeded", "deadline_exceeded",
    "failed_precondition", "forbidden", "image_safety", "insufficient_quota", "internal_error", "invalid_api_key",
    "invalid_argument", "invalid_grant", "invalid_parameter", "invalid_request", "invalid_request_error",
    "missing_api_key", "missing_scrape_script", "missing_script", "missing_search_script", "missing_service_url",
    "model_armor", "model_not_found", "not_found", "not_found_error", "out_of_range", "overloaded_error",
    "payload_too_large", "payment_issue", "payment_required", "permission_denied", "permission_error",
    "prohibited_content", "quota_exceeded", "rate_limit_error", "rate_limit_exceeded", "rate_limited",
    "remote_service", "request_too_large", "resource_exhausted", "safety", "server_error", "spii",
    "too_many_requests", "unauthenticated", "unauthorized", "unknown_model", "unavailable",
    "unsupported_parameter",
)
private val safeResponseContextPattern = Regex("content_type=(?:missing|[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,64})")

internal fun String.safeProviderIdentifier(): String? {
    val normalized = lowercase()
    return normalized.takeIf {
        providerIdentifierPattern.matches(this) &&
            (it in knownSafeProviderIdentifiers || it.matches(Regex("[0-9]{1,10}")))
    }
}

fun Throwable.safeFailureDiagnostic(): String {
    val seen = mutableSetOf<Throwable>()
    val details = buildList {
        var current: Throwable? = this@safeFailureDiagnostic
        while (current != null && seen.add(current) && size < 8) {
            val detail = when (current) {
                is HttpException -> listOfNotNull(
                    current.statusCode?.let { "http=$it" },
                    current.providerCode?.safeProviderIdentifier()?.let { "provider_code=$it" },
                    current.providerType?.safeProviderIdentifier()?.let { "provider_type=$it" },
                ).joinToString(" ").ifBlank { null }
                is ProviderResponseParseException -> current.safeContext?.takeIf(safeResponseContextPattern::matches)
                else -> null
            }
            add(current::class.simpleName.orEmpty() + detail?.let { "($it)" }.orEmpty())
            current = current.cause
        }
    }
    return details.joinToString(" -> ").ifBlank { "Throwable" }
}
