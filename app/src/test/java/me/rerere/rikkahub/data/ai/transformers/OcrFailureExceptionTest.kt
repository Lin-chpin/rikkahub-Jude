package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderResponseParseException
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class OcrFailureExceptionTest {
    @Test
    fun `HTTP status drives specific image recognition guidance`() {
        assertTrue(
            OcrFailureException(OcrFailureStage.REQUEST, statusCode = 400)
                .userMessage().contains("拒绝了请求（HTTP 400）"),
        )
        assertTrue(
            OcrFailureException(OcrFailureStage.REQUEST, statusCode = 404)
                .userMessage().contains("资源未找到（HTTP 404）"),
        )
        assertTrue(
            OcrFailureException(OcrFailureStage.REQUEST, statusCode = 408)
                .userMessage().contains("服务端报告请求超时（HTTP 408）"),
        )
        assertTrue(
            OcrFailureException(OcrFailureStage.REQUEST, statusCode = 413)
                .userMessage().contains("请求过大（HTTP 413）"),
        )
        assertTrue(
            OcrFailureException(OcrFailureStage.REQUEST, statusCode = 429)
                .userMessage().contains("限流或额度限制"),
        )
        assertTrue(
            OcrFailureException(OcrFailureStage.REQUEST, statusCode = 503)
                .userMessage().contains("服务端返回 HTTP 503"),
        )
    }

    @Test
    fun `cause chain identifies timeout without claiming server fault`() {
        val error = OcrFailureException(
            stage = OcrFailureStage.REQUEST,
            cause = HttpException("request failed", cause = SocketTimeoutException("timed out")),
        )

        assertTrue(error.userMessage().contains("超时"))
        assertTrue(error.userMessage().contains("无法确认"))
    }

    @Test
    fun `response stage explains parse boundary without guessing which side caused it`() {
        assertTrue(
            OcrFailureException(OcrFailureStage.RESPONSE).userMessage()
                .contains("响应解析失败"),
        )
    }

    @Test
    fun `provider parse exception is reported as response parsing even during request call`() {
        val error = OcrFailureException(
            OcrFailureStage.REQUEST,
            cause = ProviderResponseParseException("invalid event", IllegalArgumentException()),
        )

        assertTrue(error.userMessage().contains("响应解析失败"))
    }

    @Test
    fun `structured provider code survives OCR failure wrapping`() {
        val error = OcrFailureException(
            OcrFailureStage.REQUEST,
            cause = HttpException("opaque", statusCode = 401, providerCode = "payment_issue"),
        )

        assertTrue(error.userMessage().contains("额度或付款状态"))
    }
}
