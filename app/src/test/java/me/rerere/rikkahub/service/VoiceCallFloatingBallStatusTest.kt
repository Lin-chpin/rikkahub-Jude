package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCallFloatingBallStatusTest {
    @Test
    fun visualProgressTakesPriorityOverListeningAndKeepsTheImageCount() {
        assertEquals("截屏中\n3/10", voiceCallFloatingBallStatus("正在截屏中 · 正在持续聆听 · 3/10"))
        assertEquals("识图中\n3/10", voiceCallFloatingBallStatus("正在识图中 · 正在持续聆听 · 3/10"))
        assertEquals("发送中\n3/10", voiceCallFloatingBallStatus("正在识图并发送3张画面 · 正在持续聆听 · 3/10"))
        assertEquals("可以说话\n10/10", voiceCallFloatingBallStatus("已累计10张画面 · 正在持续聆听 · 10/10"))
        assertEquals("AI回复中\n3/10", voiceCallFloatingBallStatus("已累计3张画面 · AI 正在回复中 · 3/10"))
        assertEquals("正在聆听\n3/10", voiceCallFloatingBallStatus("正在共享屏幕中 · 正在持续聆听 · 3/10"))
        assertEquals("连接中\n3/10", voiceCallFloatingBallStatus("已累计3张画面 · 正在连接 ASR... · 3/10"))
    }

    @Test
    fun visualSendStatusHandsOffToReplyStatusAfterTheResponseArrives() {
        assertEquals(
            "发送中\n3/10",
            voiceCallFloatingBallStatus("正在识图并发送3张画面 · AI 正在回复中 · 3/10"),
        )
        assertEquals(
            "AI回复中\n3/10",
            voiceCallFloatingBallStatus("画面已发送 · AI 正在回复中 · 3/10"),
        )
        assertEquals("已发送\n3/10", voiceCallFloatingBallStatus("画面已发送 · 3/10"))
    }

    @Test
    fun actionableAsrFailuresAreReducedToSafeShortStatuses() {
        assertEquals(
            "Key/权限错误\n3/10",
            voiceCallFloatingBallStatus("正在共享屏幕中 · ElevenLabs Scribe 鉴权失败，请检查 API Key 和语音识别权限。 · 3/10"),
        )
        assertEquals(
            "额度/限流",
            voiceCallFloatingBallStatus("ElevenLabs Scribe 请求受限或额度不足，请检查账户用量后重试。"),
        )
        assertEquals(
            "服务不可用",
            voiceCallFloatingBallStatus("ElevenLabs Scribe 服务暂时不可用，请稍后重试。"),
        )
        assertEquals("ASR失败", voiceCallFloatingBallStatus("ASR错误：连接已断开"))
        assertEquals("Key/权限错误", voiceCallFloatingBallStatus("ASR错误：HTTP 403"))
        assertEquals("额度/限流", voiceCallFloatingBallStatus("ASR错误：HTTP 429"))
        assertEquals("服务不可用", voiceCallFloatingBallStatus("ASR错误：HTTP 502"))
    }

    @Test
    fun failuresRemainVisibleWithoutAnImageCount() {
        assertEquals("ASR失败", voiceCallFloatingBallStatus("正在共享屏幕中 · ASR连接失败"))
        assertEquals("ASR失败\n3/10", voiceCallFloatingBallStatus("已累计3张画面 · ASR连接失败 · 3/10"))
        assertEquals("发送失败\n3/10", voiceCallFloatingBallStatus("画面识图或发送失败 · 正在持续聆听 · 3/10"))
        assertEquals("已静音", voiceCallFloatingBallStatus("聆听已静音"))
        assertEquals("已静音\n3/10", voiceCallFloatingBallStatus("已累计3张画面 · 聆听已静音 · 3/10"))
        assertNull(voiceCallFloatingBallStatus(""))
    }
}
