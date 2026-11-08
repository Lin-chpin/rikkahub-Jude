package me.rerere.asr.providers

import me.rerere.asr.ASRProviderSetting
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevenLabsRealtimeASRProtocolTest {
    @Test
    fun realtimeServerErrorsMapToActionableChineseMessagesWithoutServerText() {
        assertTrue(elevenLabsRealtimeASRErrorMessage("auth_error").contains("鉴权失败"))
        assertTrue(elevenLabsRealtimeASRErrorMessage("unaccepted_terms").contains("接受 Scribe 服务条款"))
        assertTrue(elevenLabsRealtimeASRErrorMessage("resource_exhausted").contains("资源耗尽"))
        assertTrue(elevenLabsRealtimeASRErrorMessage("transcriber_error").contains("处理失败"))
        assertEquals(
            "ElevenLabs Scribe 报告了无法识别的错误，具体原因无法确认。",
            elevenLabsRealtimeASRErrorMessage("server supplied free text"),
        )
        assertTrue(elevenLabsRealtimeASRErrorMessage("unrecognized_error").contains("报告了无法识别的错误"))
        assertTrue(!elevenLabsRealtimeASRErrorMessage("unrecognized_error").contains("连接失败"))
    }

    @Test
    fun malformedEventsAndNestedProviderErrorsAreClassifiedAtTheResponseLayer() {
        assertNull(parseElevenLabsRealtimeEvent("not json"))
        assertNull(parseElevenLabsRealtimeEvent("""{"text":"transcript without event type"}"""))
        assertNull(parseElevenLabsRealtimeEvent("""{"message_type":42}"""))

        val authEvent = requireNotNull(
            parseElevenLabsRealtimeEvent(
                """{"message_type":"error","error":{"type":"auth_error","code":"ignored"}}""",
            ),
        )
        assertEquals("auth_error", authEvent.errorType)

        val codeOnlyEvent = requireNotNull(
            parseElevenLabsRealtimeEvent(
                """{"message_type":"error","error":{"type":"unknown","code":"payment_issue"}}""",
            ),
        )
        val code = codeOnlyEvent.errorType
        assertEquals("payment_issue", code)
        assertTrue(elevenLabsRealtimeASRErrorMessage(code).contains("付款状态或可用额度"))

        val unknownEvent = requireNotNull(parseElevenLabsRealtimeEvent("""{"message_type":"future_server_event"}"""))
        assertEquals("future_server_event", unknownEvent.messageType)
        assertNull(unknownEvent.errorType)
    }

    @Test
    fun realtimeHttpErrorsDistinguishAuthenticationRateLimitAndServerOutage() {
        assertTrue(elevenLabsRealtimeASRErrorMessage(httpStatus = 401).contains("鉴权失败"))
        assertTrue(elevenLabsRealtimeASRErrorMessage(httpStatus = 403).contains("拒绝访问"))
        assertTrue(elevenLabsRealtimeASRErrorMessage(httpStatus = 429).contains("限流或额度限制"))
        assertTrue(elevenLabsRealtimeASRErrorMessage(httpStatus = 503).contains("服务端错误（HTTP 503）"))
        assertTrue(elevenLabsRealtimeASRErrorMessage(httpStatus = 404).contains("未找到请求的资源"))
        assertTrue(elevenLabsRealtimeASRErrorMessage(httpStatus = 400).contains("拒绝了请求（HTTP 400）"))
        assertTrue(elevenLabsRealtimeASRErrorMessage(httpStatus = 408).contains("请求超时"))
    }

    @Test
    fun realtimeTransportErrorsKeepTheirKnownFailureLayer() {
        assertTrue(elevenLabsRealtimeASRErrorMessage(transportError = UnknownHostException("private host")).contains("解析 ElevenLabs Scribe 域名"))
        assertTrue(elevenLabsRealtimeASRErrorMessage(transportError = SSLException("certificate")).contains("安全连接"))
        assertTrue(elevenLabsRealtimeASRErrorMessage(transportError = SocketTimeoutException("read timeout")).contains("响应超时"))
        assertTrue(elevenLabsRealtimeASRErrorMessage(transportError = IOException("closed")).contains("连接中断"))
    }

    @Test
    fun websocketEndpointUsesRealtimeScribeAndOptionalLanguage() {
        val endpoint = ASRProviderSetting.ElevenLabsRealtime(language = "zh-CN").websocketEndpoint()

        assertTrue(endpoint.startsWith("wss://api.elevenlabs.io/v1/speech-to-text/realtime?"))
        assertTrue(endpoint.contains("model_id=scribe_v2_realtime"))
        assertTrue(endpoint.contains("audio_format=pcm_16000"))
        assertTrue(endpoint.contains("commit_strategy=vad"))
        assertTrue(endpoint.contains("keepalive_interval_ms=1000"))
        assertTrue(endpoint.contains("language_code=zh-CN"))
    }

    @Test
    fun websocketEndpointCorrectsCommonChineseLanguageCodeTypos() {
        val endpoint = ASRProviderSetting.ElevenLabsRealtime(language = "zn").websocketEndpoint()

        assertTrue(endpoint.contains("language_code=zh"))
    }
}
