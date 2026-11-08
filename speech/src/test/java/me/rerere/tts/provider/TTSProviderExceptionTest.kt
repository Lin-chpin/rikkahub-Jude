package me.rerere.tts.provider

import me.rerere.tts.controller.safeTtsErrorMessage
import java.net.ConnectException
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderExceptionTest {
    @Test
    fun socketInterruptionDoesNotClaimConnectionSetupFailed() {
        assertTrue(safeTtsErrorMessage(ConnectException("refused")).contains("无法建立语音服务连接"))
        assertTrue(safeTtsErrorMessage(SocketException("connection reset")).contains("连接中断，具体阶段无法确认"))
    }

    @Test
    fun extractsOnlyAllowlistedStructuredDetails() {
        val details = parseTTSProviderErrorDetails(
            """{"error":{"type":"authentication_error","code":"invalid_api_key","message":"secret text"}}""",
        )

        assertEquals(TTSProviderErrorCode.INVALID_API_KEY, details.code)
        assertEquals(TTSProviderErrorCode.AUTHENTICATION_ERROR, details.type)
        assertTrue("""{"error":{"code":"https://host/key?token=secret"}}""".let {
            parseTTSProviderErrorDetails(it).code == null
        })
        assertNull(parseTTSProviderErrorDetails("not json").code)
    }

    @Test
    fun extractsElevenLabsDetailStatusAsSafeCode() {
        val details = parseTTSProviderErrorDetails(
            """{"detail":{"status":"payment_issue","message":"account details"}}""",
        )

        assertEquals(TTSProviderErrorCode.PAYMENT_ISSUE, details.code)
    }

    @Test
    fun providerExceptionRetainsStructuredStatusWithoutRawResponseText() {
        val failure = TTSProviderException(
            provider = TTSProviderKind.ELEVENLABS,
            statusCode = 401,
            providerCode = TTSProviderErrorCode.PAYMENT_ISSUE,
        )

        assertEquals(401, failure.statusCode)
        assertEquals(TTSProviderErrorCode.PAYMENT_ISSUE, failure.providerCode)
        assertTrue(failure.message.orEmpty().contains("HTTP 401"))
        assertTrue(failure.message.orEmpty().contains("payment_issue"))
        assertTrue(!failure.message.orEmpty().contains("https://"))
    }

    @Test
    fun sseFailureKeepsSafeProviderCodeAlongsideHttpStatus() {
        val failure = createTTSProviderSseFailure(
            provider = TTSProviderKind.ELEVENLABS,
            statusCode = 401,
            cause = null,
            providerCode = "payment_issue",
            providerType = "authentication_error",
        ) as TTSProviderException

        assertEquals(401, failure.statusCode)
        assertEquals(TTSProviderErrorCode.PAYMENT_ISSUE, failure.providerCode)
        assertEquals(TTSProviderErrorCode.AUTHENTICATION_ERROR, failure.providerType)
    }
}
