package me.rerere.rikkahub.data.ai

import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderResponseParseException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class ChatRequestFailureTest {
    @Test
    fun `HTTP errors have Chinese explanations while original detail survives`() {
        val error = HttpException("upstream quota limit exceeded", 429)
        assertTrue(error.chatErrorExplanation().contains("限流或额度"))
        assertEquals("upstream quota limit exceeded", error.message)
        assertTrue(HttpException("Unauthorized", 401).chatErrorExplanation().contains("身份验证"))
        assertTrue(HttpException("Forbidden", 403).chatErrorExplanation().contains("拒绝了访问"))
        assertTrue(HttpException("Not Found", 404).chatErrorExplanation().contains("请求资源未找到"))
        assertTrue(Exception("Failed to get response: 503 temporary error").chatErrorExplanation().contains("具体原因无法确认"))
        assertTrue(HttpException("opaque", statusCode = 422).chatErrorExplanation()
            .contains("接口返回 HTTP 422"))
        assertTrue(HttpException("Provider reported an error").chatErrorExplanation()
            .contains("接口报告请求失败"))
        assertTrue(HttpException("opaque", providerCode = "rate_limit_exceeded")
            .chatErrorExplanation().contains("限流"))
        assertTrue(HttpException("opaque", providerType = "rate_limit_error")
            .chatErrorExplanation().contains("限流"))
        assertTrue(HttpException("opaque", providerCode = "PROHIBITED_CONTENT")
            .chatErrorExplanation().contains("内容策略拒绝"))
        assertTrue(HttpException("opaque", providerCode = "content_policy_violation")
            .chatErrorExplanation().contains("内容策略拒绝"))
        assertTrue(HttpException("opaque", providerCode = "context_length_exceeded")
            .chatErrorExplanation().contains("超过了模型或服务限制"))
        assertTrue(HttpException("opaque", providerCode = "server_error")
            .chatErrorExplanation().contains("接口报告服务端错误"))
        assertTrue(HttpException("opaque", statusCode = 401, providerCode = "payment_issue")
            .chatErrorExplanation().contains("额度或付款"))
        assertTrue(
            HttpException(
                "bad error body",
                statusCode = 401,
                cause = ProviderResponseParseException("parse failed", IllegalArgumentException()),
            ).chatErrorExplanation().contains("身份验证失败"),
        )
    }

    @Test
    fun `interruption does not claim client or server fault`() {
        val explanation = IOException("SSE stream closed before [DONE]").chatErrorExplanation()
        assertTrue(explanation.contains("I/O 阶段失败"))
        assertFalse(explanation.contains("客户端故障"))
        assertFalse(explanation.contains("服务端故障"))
        assertTrue(SocketTimeoutException("timed out").chatErrorExplanation().contains("无法仅据此确认"))
        assertTrue(ProviderResponseParseException("invalid response", IllegalArgumentException())
            .chatErrorExplanation().contains("响应解析失败"))
        assertTrue(
            HttpException(
                "bad error body",
                statusCode = 401,
                cause = ProviderResponseParseException("parse failed", IllegalArgumentException()),
            ).chatErrorExplanation().contains("身份验证失败"),
        )
        assertEquals(
            "本次操作未能完成，具体原因无法确认。",
            IllegalStateException("没有配置模型").chatErrorExplanation(),
        )
        assertTrue(Exception("opaque English failure").chatErrorExplanation().contains("具体原因无法确认"))
    }
}
