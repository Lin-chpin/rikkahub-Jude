package me.rerere.rikkahub.data.ai

import me.rerere.common.android.Logging
import me.rerere.common.http.RequestLogPrivacy
import me.rerere.common.http.privateRequestFailure
import me.rerere.common.http.suppressContentLogs
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class RequestLoggingPrivacyTest {
    @Test fun `private request body is never read for logging and errors do not echo content`() {
        val request = Request.Builder().url("https://example.com/model?key=secret")
            .suppressContentLogs(true)
            .post(object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun writeTo(sink: BufferedSink) { error("Private body must not be read by the logger") }
            }).build()
        assertSame(RequestLogPrivacy, request.tag(RequestLogPrivacy::class.java))
        val chain = object : Interceptor.Chain {
            override fun request() = request
            override fun proceed(request: Request) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(429).message("Too Many Requests")
                .header("Retry-After", "30")
                .body("""{"error":{"code":"insufficient_quota","message":"screenshot-secret"}}""".toResponseBody())
                .build()
            override fun connection(): Connection? = null
            override fun call(): Call = error("unused")
            override fun connectTimeoutMillis() = 0
            override fun readTimeoutMillis() = 0
            override fun writeTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
            override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
        }
        Logging.clear()
        try {
            RequestLoggingInterceptor().intercept(chain).close()
            val log = Logging.getRequestLogs().single()
            assertEquals("[REDACTED]", log.requestBody)
            assertFalse(log.url.contains("secret"))
            assertTrue(log.requestHeaders.isEmpty())
            assertTrue(log.responseHeaders.isEmpty())
            assertEquals(429, log.responseCode)
            assertTrue(requireNotNull(log.error).contains("provider_code=INSUFFICIENT_QUOTA"))
            assertTrue(requireNotNull(log.error).contains("retry_after_seconds=30.0"))
            assertFalse(requireNotNull(log.error).contains("screenshot-secret"))
            val failure = privateRequestFailure(SocketTimeoutException("screenshot-secret"), null)
            assertTrue(failure is SocketTimeoutException)
            assertFalse(failure.stackTraceToString().contains("screenshot-secret"))
        } finally { Logging.clear() }
    }
}
