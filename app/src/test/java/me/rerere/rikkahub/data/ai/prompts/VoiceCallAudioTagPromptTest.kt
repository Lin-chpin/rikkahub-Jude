package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.voice.VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallAudioTagPromptTest {
    @Test
    fun givesSecondPassModelCuteCharacterVoiceDirection() {
        val prompt = buildVoiceCallAudioTagPrompt(VoiceCallAudioTagFormat.ELEVEN_LABS_V3)

        assertTrue(prompt.contains("voice director"))
        assertTrue(prompt.contains("cute, affectionate"))
        assertTrue(prompt.contains("act coy"))
        assertTrue(prompt.contains("one dominant direction per segment"))
    }

    @Test
    fun keepsDirectionAudibleAndTextClientOwned() {
        val prompt = buildVoiceCallAudioTagPrompt(VoiceCallAudioTagFormat.ELEVEN_LABS_V3)

        assertTrue(prompt.contains("audible performance"))
        assertTrue(prompt.contains("Never infer visual actions"))
        assertTrue(prompt.contains("never add, remove, or alter text"))
        assertTrue(prompt.contains("MUST call `$VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME`"))
        assertFalse(prompt.contains("[breathing heavily]"))
    }

    @Test
    fun marksCommonAudibleTagsAndAllowsMiniMaxNone() {
        val prompt = buildVoiceCallAudioTagPrompt(VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8)

        assertTrue(prompt.contains("LAUGHS, CHUCKLE, BREATH, SIGHS"))
        assertTrue(prompt.contains("Use NONE"))
        assertTrue(prompt.contains("Do not force a tag"))
    }
}
