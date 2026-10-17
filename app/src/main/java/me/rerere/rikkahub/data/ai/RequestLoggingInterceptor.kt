package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.common.http.RequestLogPrivacy
import me.rerere.common.http.privateHttpFailureDetails
import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val privateRequest = request.tag(RequestLogPrivacy::class.java) != null
        val loggedUrl = if (privateRequest) request.url.newBuilder().query(null).build().toString() else request.url.toString()

        val requestHeaders = if (privateRequest) emptyMap() else request.headers.toMap()
        val requestBody = if (privateRequest || request.url.host == OPENAI_AUTH_HOST) {
            REDACTED
        } else {
            request.body?.toLogString()
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = if (privateRequest) e::class.simpleName else e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = loggedUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = if (privateRequest) emptyMap() else response.headers.toMap()
        if (privateRequest && !response.isSuccessful) error = privateHttpFailureDetails(response)

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = loggedUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun RequestBody.toLogString(): String {
        val buffer = Buffer()
        writeTo(buffer)
        val totalBytes = buffer.size
        val preview = buffer.readUtf8(totalBytes.coerceAtMost(MAX_LOGGED_BODY_BYTES))
        return if (totalBytes > MAX_LOGGED_BODY_BYTES) {
            "$preview\n[truncated request body: $totalBytes bytes total]"
        } else {
            preview
        }
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            if (name.lowercase() in SENSITIVE_HEADERS) REDACTED else get(name) ?: ""
        }
    }

    private companion object {
        const val OPENAI_AUTH_HOST = "auth.openai.com"
        const val MAX_LOGGED_BODY_BYTES = 256 * 1024L
        const val REDACTED = "[REDACTED]"
        val SENSITIVE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "cookie",
            "set-cookie",
            "chatgpt-account-id",
        )
    }
}
