package me.rerere.rikkahub.personal.heartbeat

import android.database.sqlite.SQLiteException
import me.rerere.ai.util.HttpException
import me.rerere.common.http.privateFailureStatusCode
import kotlinx.coroutines.TimeoutCancellationException
import java.nio.file.FileSystemException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

object HeartbeatFailureClassifier {
    fun classify(error: Throwable): HeartbeatRunReason {
        val causes = error.causeChain().toList()
        val providerErrors = causes.filterIsInstance<HttpException>()
            .flatMap { listOfNotNull(it.providerCode, it.providerType) }
            .map { it.lowercase() }
            .toSet()
        when {
            providerErrors.any { it in setOf("invalid_api_key", "authentication_error", "auth_error", "unauthenticated", "missing_api_key", "invalid_grant", "unauthorized") } ->
                return HeartbeatRunReason.AUTHENTICATION_FAILED
            providerErrors.any { it in setOf("rate_limit_exceeded", "rate_limited", "too_many_requests", "rate_limit_error") } ->
                return HeartbeatRunReason.RATE_LIMITED
            providerErrors.any { it in setOf("insufficient_quota", "quota_exceeded", "payment_issue", "billing_hard_limit_reached") } ->
                return HeartbeatRunReason.PAYMENT_REQUIRED
            providerErrors.any { it in setOf("permission_denied", "forbidden", "access_denied", "permission_error") } ->
                return HeartbeatRunReason.ACCESS_DENIED
            providerErrors.any { it in setOf("safety", "prohibited_content", "content_policy_violation", "blocklist", "spii", "image_safety", "model_armor") } ->
                return HeartbeatRunReason.CONTENT_POLICY_REJECTED
            providerErrors.any { it in setOf("invalid_request_error", "invalid_request", "invalid_argument", "bad_request", "invalid_parameter", "unsupported_parameter") } ->
                return HeartbeatRunReason.REQUEST_REJECTED
            providerErrors.any { it in setOf("not_found", "not_found_error", "model_not_found", "unknown_model") } ->
                return HeartbeatRunReason.RESOURCE_NOT_FOUND
            providerErrors.any { it in setOf("request_too_large", "payload_too_large", "context_length_exceeded") } ->
                return HeartbeatRunReason.REQUEST_TOO_LARGE
            providerErrors.any { it in setOf("server_error", "api_error", "internal_error", "unavailable", "overloaded_error") } ->
                return HeartbeatRunReason.SERVER_ERROR
        }
        val status = httpStatusCode(error)
        status?.let { code ->
            return when {
                code == 400 -> HeartbeatRunReason.REQUEST_REJECTED
                code == 401 -> HeartbeatRunReason.AUTHENTICATION_FAILED
                code == 402 -> HeartbeatRunReason.PAYMENT_REQUIRED
                code == 403 -> HeartbeatRunReason.ACCESS_DENIED
                code == 404 -> HeartbeatRunReason.RESOURCE_NOT_FOUND
                code == 408 -> HeartbeatRunReason.SERVICE_REQUEST_TIMEOUT
                code == 413 -> HeartbeatRunReason.REQUEST_TOO_LARGE
                code == 429 -> HeartbeatRunReason.RATE_LIMITED
                code in 500..599 -> HeartbeatRunReason.SERVER_ERROR
                else -> HeartbeatRunReason.HTTP_ERROR
            }
        }
        return when {
            causes.any { it is SocketTimeoutException } -> HeartbeatRunReason.NETWORK_TIMEOUT
            causes.any { it is TimeoutCancellationException || it is TimeoutException } -> HeartbeatRunReason.REQUEST_TIMEOUT
            causes.any { it is SQLiteException || it is FileSystemException } -> HeartbeatRunReason.STORAGE_FAILURE
            causes.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException
            } -> HeartbeatRunReason.NETWORK_UNAVAILABLE
            causes.any { it is SSLException } -> HeartbeatRunReason.SECURE_CONNECTION_FAILURE
            causes.any { it is SocketException } -> HeartbeatRunReason.NETWORK_TRANSPORT_FAILURE
            else -> HeartbeatRunReason.GENERATION_FAILURE
        }
    }

    fun httpStatusCode(error: Throwable): Int? = error.causeChain()
        .filterIsInstance<HttpException>()
        .firstNotNullOfOrNull { it.statusCode }
        ?: privateFailureStatusCode(error)

    private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = this@causeChain
        while (current != null && visited.add(current)) {
            yield(current)
            current = current.cause
        }
    }
}
