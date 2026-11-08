package me.rerere.tts.provider.providers

import me.rerere.tts.provider.TTSResponseParsingException
import org.junit.Assert.assertThrows
import org.junit.Test

class TTSResponseParsingTest {
    @Test
    fun malformedProviderAudioResponsesKeepTheirParsingStage() {
        assertThrows(TTSResponseParsingException::class.java) {
            decodeMiniMaxAudioChunk("not json")
        }
        assertThrows(TTSResponseParsingException::class.java) {
            parseQwenSseAudioData("not json")
        }
        assertThrows(TTSResponseParsingException::class.java) {
            decodeGeminiTtsAudio("not json")
        }
        assertThrows(TTSResponseParsingException::class.java) {
            decodeMiMoAudioData("not json")
        }
    }
}
