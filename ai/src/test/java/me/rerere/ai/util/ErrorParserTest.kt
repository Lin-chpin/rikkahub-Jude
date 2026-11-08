package me.rerere.ai.util

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorParserTest {
    @Test
    fun `structured provider error fields and HTTP status survive wrapping`() {
        val parsed = Json.parseToJsonElement(
            """{"error":{"code":"rate_limit_exceeded","type":"rate_limit_error","message":"slow down"}}"""
        ).parseErrorDetail()
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .body("{}".toResponseBody())
            .build()

        val wrapped = parsed.withHttpStatus(response) as HttpException

        assertEquals("Provider reported an error", wrapped.message)
        assertEquals(429, wrapped.statusCode)
        assertEquals("rate_limit_exceeded", wrapped.providerCode)
        assertEquals("rate_limit_error", wrapped.providerType)
        assertEquals(parsed, wrapped.cause)
    }

    @Test
    fun `malformed provider body is not copied into exception message or diagnostics`() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body("private response content".toResponseBody())
            .build()

        val error = response.toHttpException()

        assertEquals(400, error.statusCode)
        assertFalse(error.message.orEmpty().contains("private response content"))
        assertFalse(error.safeFailureDiagnostic().contains("private response content"))
        assertTrue(error.cause is ProviderResponseParseException)
        assertTrue(error.cause?.cause is SerializationException)
        assertTrue(error.safeFailureDiagnostic().contains(error.cause?.cause?.javaClass?.simpleName.orEmpty()))
    }

    @Test
    fun `safe failure diagnostics keep status and allowlisted fields but omit messages`() {
        val error = HttpException(
            "private message",
            statusCode = 429,
            providerCode = "rate_limit_exceeded",
            providerType = "rate_limit_error",
        )

        val detail = error.safeFailureDiagnostic()

        assertTrue(detail.contains("http=429"))
        assertTrue(detail.contains("provider_code=rate_limit_exceeded"))
        assertTrue(detail.contains("provider_type=rate_limit_error"))
        assertFalse(detail.contains("private message"))
    }

    @Test
    fun `response parse diagnostics keep safe content type only`() {
        val error = ProviderResponseParseException(
            "invalid content type with secret parameter",
            IllegalStateException("raw response details"),
            safeContext = "content_type=text/plain",
        )
        val diagnostic = error.safeFailureDiagnostic()

        assertTrue(diagnostic.contains("content_type=text/plain"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("raw response details"))
    }

    @Test
    fun `provider fields survive nested cause wrapping without treating provider code as HTTP status`() {
        val providerError = Json.parseToJsonElement(
            """{"error":{"code":429,"type":"rate_limit_error","message":"slow down"}}"""
        ).parseErrorDetail()
        val parseStage = ProviderResponseParseException("event wrapper", IllegalStateException("outer", providerError))
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1)
            .code(502)
            .message("Bad Gateway")
            .body("{}".toResponseBody())
            .build()

        val wrapped = parseStage.withHttpStatus(response) as HttpException

        assertEquals(null, providerError.statusCode)
        assertEquals(502, wrapped.statusCode)
        assertEquals("429", wrapped.providerCode)
        assertEquals("rate_limit_error", wrapped.providerType)
    }

    @Test
    fun `unsafe provider identifiers and provider messages are not exposed`() {
        val parsed = Json.parseToJsonElement(
            """{"error":{"code":"contains private data","type":"invalid_request","message":"bad request"}}"""
        ).parseErrorDetail()

        assertEquals("Provider reported an error", parsed.message)
        assertEquals(null, parsed.providerCode)
        assertEquals("invalid_request", parsed.providerType)
        assertEquals(null, Json.parseToJsonElement(
            """{"error":{"code":"sk-live-secret-value","type":"api_key_secret"}}""",
        ).parseErrorDetail().providerCode)
    }
}
