package me.rerere.rikkahub.data.ai

import me.rerere.common.http.privateFailureDiagnosticDetail
import me.rerere.common.http.privateFailureStatusCode
import me.rerere.common.http.privateHttpFailureDetails
import me.rerere.common.http.privateRequestFailure
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant

class PrivateHttpDiagnosticsTest {
    private fun response(body: String, status: Int = 429) = Response.Builder()
        .request(Request.Builder().url("https://example.com/model?key=secret-key").build())
        .protocol(Protocol.HTTP_1_1).code(status).message("error")
        .header("Retry-After", "45").header("x-ratelimit-remaining-requests", "0")
        .header("x-sensitive-header", "secret-header").body(body.toResponseBody()).build()

    @Test fun geminiQuotaAndRetryMetadataSurviveWithoutPrivateContentOrBodyConsumption() {
        val body = """{"error":{
          "code":429,"status":"RESOURCE_EXHAUSTED","message":"secret-screen-content",
          "details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{
              "quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_input_token_count",
              "quotaId":"GenerateContentInputTokensPerModelPerMinute-FreeTier",
              "description":"secret-project-and-screen","quotaDimensions":{"project":"secret-project"}}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"11.5s"},
            {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"RATE_LIMIT_EXCEEDED"}
          ]}}"""
        response(body).use {
            val detail = privateHttpFailureDetails(it)
            assertTrue(detail.contains("http_status=429"))
            assertTrue(detail.contains("provider_status=RESOURCE_EXHAUSTED"))
            assertTrue(detail.contains("provider_reason=RATE_LIMIT_EXCEEDED"))
            assertTrue(detail.contains("quota=input_tokens_per_minute_free_tier"))
            assertTrue(detail.contains("retry_after_seconds=45.0"))
            assertTrue(detail.contains("provider_retry_seconds=11.5"))
            assertTrue(detail.contains("remaining_requests=0"))
            assertFalse(detail.contains("secret"))
            assertEquals(body, it.body.string())
        }
    }

    @Test fun compatibleQuotaErrorsAndHttpDateRetryHintsAreSupported() {
        response("""{"error":{"code":"insufficient_quota","type":"rate_limit_error","message":"secret"}}""")
            .newBuilder().header("Retry-After", "Thu, 15 Oct 2026 14:01:00 GMT").build().use {
                val now = Instant.parse("2026-10-15T14:00:00Z").toEpochMilli()
                val detail = privateHttpFailureDetails(it, now)
                assertTrue(detail.contains("provider_code=INSUFFICIENT_QUOTA"))
                assertTrue(detail.contains("provider_type=RATE_LIMIT_ERROR"))
                assertTrue(detail.contains("retry_after_seconds=60.0"))
                assertFalse(detail.contains("secret"))
            }
    }

    @Test fun transportExceptionKindsArePreservedAndOnlySanitizedHttpCauseIsRetained() {
        response("""{"error":{"code":"rate_limit_exceeded","message":"secret-screen"}}""").use {
            val failure = privateRequestFailure(IOException("secret-network"), 429, it)
            assertTrue(failure is IOException)
            assertEquals(429, privateFailureStatusCode(failure))
            assertTrue(requireNotNull(privateFailureDiagnosticDetail(failure)).contains("RATE_LIMIT_EXCEEDED"))
            assertFalse(failure.stackTraceToString().contains("secret"))
        }
        val timeout = privateRequestFailure(SocketTimeoutException("secret"), null)
        assertTrue(timeout is SocketTimeoutException)
        assertNull(privateFailureStatusCode(timeout))
        assertFalse(timeout.stackTraceToString().contains("secret"))
    }

    @Test fun unknownFieldsMalformedBodiesAndUnsafeRetryHeadersDoNotLeakOrReplaceHttpStatus() {
        listOf("<html>secret</html>", """{"error":{"code":"secret-key","status":"secret","type":"secret"}}""")
            .forEach { body ->
                response(body).newBuilder().header("Retry-After", "secret").build().use {
                    val detail = privateHttpFailureDetails(it)
                    assertTrue(detail.contains("http_status=429"))
                    assertTrue(detail.contains("unrecognized"))
                    assertFalse(detail.contains("secret"))
                    assertFalse(detail.contains("retry_after_seconds"))
                }
            }
    }
}
