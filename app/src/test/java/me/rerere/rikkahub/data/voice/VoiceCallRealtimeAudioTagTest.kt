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
    fun asksRegularV4ToTagEverySentenceLikeTurboAndStayInFirstPerson() {
        val prompt = buildVoiceCallRealtimeAudioTagPrompt(VoiceCallAudioTagFormat.ELEVEN_LABS_V4)
            .replace(Regex("\\s+"), " ")

        assertTrue(prompt.contains("first person"))
        assertTrue(prompt.contains("For every completed spoken sentence"))
        assertTrue(prompt.contains("choose the closest catalog event"))
        assertFalse(prompt.contains("No tag is the normal choice"))
    }
}
