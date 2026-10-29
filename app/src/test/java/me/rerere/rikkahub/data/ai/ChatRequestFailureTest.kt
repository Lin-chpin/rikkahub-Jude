package me.rerere.rikkahub.data.ai

import me.rerere.ai.util.HttpException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ChatRequestFailureTest {
    @Test
    fun `HTTP errors have Chinese explanations while original detail survives`() {
        val error = HttpException("upstream quota limit exceeded", 429)
        assertTrue(error.chatErrorExplanation().contains("请求频率或额度"))
        assertEquals("upstream quota limit exceeded", error.message)
        assertTrue(HttpException("Unauthorized", 401).chatErrorExplanation().contains("身份验证"))
        assertTrue(Exception("Failed to get response: 503 temporary error").chatErrorExplanation().contains("暂时无法"))
    }

    @Test
    fun `interruption does not claim client or server fault`() {
        val explanation = IOException("SSE stream closed before [DONE]").chatErrorExplanation()
        assertTrue(explanation.contains("已收到的内容会保留"))
        assertFalse(explanation.contains("客户端故障"))
        assertFalse(explanation.contains("服务端故障"))
        assertEquals("没有配置模型", IllegalStateException("没有配置模型").chatErrorExplanation())
        assertTrue(Exception("opaque English failure").chatErrorExplanation().contains("查看错误详情"))
    }
}
