package me.rerere.common.http

import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

/** Local request tag; it is never serialized as an HTTP header or model parameter. */
data object RequestLogPrivacy

fun Request.Builder.suppressContentLogs(enabled: Boolean): Request.Builder =
    tag(RequestLogPrivacy::class.java, RequestLogPrivacy.takeIf { enabled })

fun privateRequestFailure(error: Throwable?, statusCode: Int?, response: Response? = null): Exception {
    val httpFailure = statusCode?.takeIf { it >= 400 }?.let {
        PrivateHttpFailure(it, response?.let { body -> privateHttpFailureDetails(body) } ?: "http_status=$it")
    }
    val description = httpFailure?.message
        ?: "Private request failed: ${statusCode?.let { "HTTP $it" } ?: error?.javaClass?.simpleName ?: "unknown"}"
    return when (error) {
        is SocketTimeoutException -> SocketTimeoutException(description).apply { httpFailure?.let { initCause(it) } }
        is IOException -> IOException(description, httpFailure)
        else -> httpFailure ?: Exception(description)
    }
}
