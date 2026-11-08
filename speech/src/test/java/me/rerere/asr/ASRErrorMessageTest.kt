package me.rerere.asr

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import org.junit.Assert.assertTrue
import org.junit.Test

class ASRErrorMessageTest {
    @Test
    fun realtimeAsrFailuresKeepTheKnownStatusOrLayer() {
        assertTrue(realtimeAsrErrorMessage("OpenAI Realtime", httpStatus = 401).contains("身份验证失败"))
        assertTrue(realtimeAsrErrorMessage("OpenAI Realtime", httpStatus = 429).contains("限制了请求频率"))
        assertTrue(realtimeAsrErrorMessage("OpenAI Realtime", httpStatus = 503).contains("HTTP 503"))
        assertTrue(realtimeAsrErrorMessage("DashScope", responseParsingFailed = true).contains("响应解析"))
        assertTrue(realtimeAsrErrorMessage("DashScope", providerReportedFailure = true).contains("报告请求失败"))
        assertTrue(realtimeAsrErrorMessage("OpenAI Realtime", transportError = UnknownHostException()).contains("无法解析"))
        assertTrue(realtimeAsrErrorMessage("OpenAI Realtime", transportError = ConnectException("refused")).contains("无法连接"))
        assertTrue(realtimeAsrErrorMessage("OpenAI Realtime", transportError = SocketException("reset")).contains("连接中断，具体阶段无法确认"))
        assertTrue(realtimeAsrErrorMessage("OpenAI Realtime", transportError = IOException()).contains("传输失败"))
        assertTrue(realtimeAsrWebSocketClosedMessage("OpenAI Realtime", 1011).contains("WebSocket 1011"))
        assertTrue(realtimeAsrWebSocketClosedMessage("OpenAI Realtime", 1011).contains("具体原因无法确认"))
        assertTrue(isExpectedAsrWebSocketClosure(userStopped = true, code = 1000))
        assertTrue(!isExpectedAsrWebSocketClosure(userStopped = false, code = 1000))
        assertTrue(!isExpectedAsrWebSocketClosure(userStopped = true, code = 1011))
    }
}
