package me.rerere.asr.providers

import me.rerere.asr.ASRProviderSetting
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevenLabsRealtimeASRProtocolTest {
    @Test
    fun websocketEndpointUsesRealtimeScribeAndOptionalLanguage() {
        val endpoint = ASRProviderSetting.ElevenLabsRealtime(language = "zh-CN").websocketEndpoint()

        assertTrue(endpoint.startsWith("wss://api.elevenlabs.io/v1/speech-to-text/realtime?"))
        assertTrue(endpoint.contains("model_id=scribe_v2_realtime"))
        assertTrue(endpoint.contains("audio_format=pcm_16000"))
        assertTrue(endpoint.contains("commit_strategy=vad"))
        assertTrue(endpoint.contains("language_code=zh-CN"))
    }
}
