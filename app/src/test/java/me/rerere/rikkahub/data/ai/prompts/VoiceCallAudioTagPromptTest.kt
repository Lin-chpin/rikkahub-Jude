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
    fun instructsMiniMaxToReplaceLeadingInterjectionsWithAudibleTags() {
        val prompt = buildVoiceCallAudioTagPrompt(VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8)

        assertTrue(prompt.contains("嘿嘿/哈哈/嘻嘻，我好开心"))
        assertTrue(prompt.contains("LAUGHS;"))
        assertTrue(prompt.contains("SIGHS is an actual audible sigh"))
        assertTrue(prompt.contains("replacementText"))
        assertTrue(prompt.contains("exact leading text"))
        assertTrue(prompt.contains("original reply remains unchanged for display"))
    }
    @Test
    fun prefersOneMiniMaxTagPerSegmentButKeepsNoneAsFallback() {
        val prompt = buildVoiceCallAudioTagPrompt(VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8)

        assertTrue(prompt.contains("choose one tag for every segment whenever"))
        assertTrue(prompt.contains("including segments with no leading interjection"))
        assertTrue(prompt.contains("do not fall back to NONE merely because there is no word to replace"))
        assertTrue(prompt.contains("only when no supported tag is natural"))
        assertTrue(prompt.contains("LAUGHS, CHUCKLE, BREATH, SIGHS"))
    }
}
