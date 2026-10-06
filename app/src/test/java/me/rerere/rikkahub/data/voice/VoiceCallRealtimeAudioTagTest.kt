package me.rerere.rikkahub.data.voice

import me.rerere.rikkahub.data.ai.prompts.buildVoiceCallRealtimeAudioTagPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallRealtimeAudioTagTest {
    @Test
    fun holdsAnUnfinishedInlineTagUntilItsClosingBracketArrives() {
        assertEquals("你好 ", "你好 [laugh".withoutIncompleteVoiceCallAudioTag())
        assertEquals("你好 [laughs]", "你好 [laughs]".withoutIncompleteVoiceCallAudioTag())
    }

    @Test
    fun extractsInlineAudioTagsForVoiceBarDisplay() {
        assertEquals(
            listOf("[softly]", "[laughs]"),
            "[softly]你好。[laughs]".flexibleVoiceCallAudioTags(),
        )
    }

    @Test
    fun sharesFlexiblePerformanceDirectionBetweenElevenLabsV3AndV4() {
        listOf(VoiceCallAudioTagFormat.ELEVEN_LABS_V3, VoiceCallAudioTagFormat.ELEVEN_LABS_V4)
            .forEach { format ->
                val prompt = buildVoiceCallRealtimeAudioTagPrompt(format)
                    .replace(Regex("\\s+"), " ")

                assertTrue(prompt.contains("first person"))
                assertTrue(prompt.contains("Tags may be concise free-form directions"))
                assertTrue(prompt.contains("Every ElevenLabs audio tag must be written in English"))
                assertTrue(prompt.contains("Do not tag every sentence"))
                assertFalse(prompt.contains("exact catalog"))
            }
    }

    @Test
    fun parsesAndRemovesAngleBracketedMiniMaxEmotionControlMarkers() {
        val response = "__VOICE_CALL_EMOTION__: <happy>\n[softly]你好。"

        assertEquals("happy", response.voiceCallRealtimeEmotionOrNull())
        assertEquals("[softly]你好。", response.withoutVoiceCallRealtimeEmotionMarker())
    }
}
