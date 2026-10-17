package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.VoiceCallAudioSegment
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallAudioAlignmentTest {
    @Test fun interruptedWholeAudioIsAttachedOnlyOnceAndRetainsItsReplayCutoff() {
        val message = UIMessage.assistant("第一句。第二句。")
        val audio = VoiceCallAudioSegment(
            text = "第一句。第二句。", audioUri = "file:///prefix.mp3",
            format = "MP3", sampleRate = 44100, endPositionMs = 800,
        )
        val items = message.voiceCallDisplayItems(
            audioSegmentsOverride = listOf(audio), currentAssistantId = null, textGenerationPending = false,
        ).filterIsInstance<VoiceCallDisplayItem.Voice>()
        assertEquals(1, items.count { it.audioSegment != null })
        assertEquals(800L, items.first().audioSegment?.endPositionMs)
        assertNull(items.last().audioSegment)
    }

    @Test
    fun wholeReplyTranslationIsDistributedAcrossSentenceBubblesWhenBoundariesDiffer() {
        val message = UIMessage.assistant("Original one. Original two.").copy(
            voiceCallTranslations = mapOf(
                VOICE_CALL_REPLY_TRANSLATION_KEY to "译文甲。译文乙！译文丙？",
            ),
        )

        assertEquals("译文甲。\n译文乙！", message.voiceCallTranslationForDisplay(0, 2, "翻译中"))
        assertEquals("译文丙？", message.voiceCallTranslationForDisplay(1, 2, "翻译中"))
    }

    @Test
    fun incompleteTranslationStartsUpdatingTheFirstSentenceBubble() {
        val message = UIMessage.assistant("第一句。第二句。第三句。").copy(
            voiceCallTranslations = mapOf(VOICE_CALL_REPLY_TRANSLATION_KEY to "途中译文"),
        )

        assertEquals("途中译文", message.voiceCallTranslationForDisplay(0, 3, "翻译中"))
        assertNull(message.voiceCallTranslationForDisplay(1, 3, "翻译中"))
    }

    @Test
    fun savedNormalizedTimingsReplayIndividualSentencesAndRejectIncompleteTextCoverage() {
        val response = TTSResponse(
            audioData = byteArrayOf(1), format = AudioFormat.MP3,
            metadata = mapOf(
                "alignment_complete" to "true",
                "alignment_timebase" to "stream",
                "alignment_chunks" to """[
                    {"chars":["你","。"],"char_start_times_ms":[0,10],"char_durations_ms":[10,0]},
                    {"chars":["好","！"],"char_start_times_ms":[52.2449,62.2449],"char_durations_ms":[10,0]}
                ]""",
            ),
        )
        val ranges = alignedVoiceCallAudioRanges("你。好！", response)!!
        assertEquals(listOf("你。", "好！"), ranges.map { it.text })
        assertEquals(listOf(0L, 52L), ranges.map { it.startPositionMs })
        assertNull(alignedVoiceCallAudioRanges("你。好！漏掉一句。", response))
    }

    @Test
    fun packetBoundaryOverlapKeepsACompleteSentenceReplayRange() {
        val response = TTSResponse(
            audioData = byteArrayOf(1), format = AudioFormat.MP3,
            metadata = mapOf(
                "alignment_complete" to "true",
                "alignment_chunks" to """[
                    {"chars":["你"],"char_start_times_ms":[50],"char_durations_ms":[30]},
                    {"chars":["好","！"],"char_start_times_ms":[26,106],"char_durations_ms":[50,0]}
                ]""",
            ),
        )

        val range = alignedVoiceCallAudioRanges("你好！", response)!!.single()
        assertEquals("你好！", range.text)
        assertEquals(26L, range.startPositionMs)
        assertEquals(106L, range.endPositionMs)
    }

    @Test
    fun zeroDurationPunctuationAndWhitespaceDoNotLoseSentenceRanges() {
        val alignment = VoiceCallAudioAlignment()
        assertTrue(alignment.append("""{"chars":["你","好","。"],"char_start_times_ms":[0,80,160],"char_durations_ms":[80,80,0]}"""))
        assertTrue(alignment.append("""{"chars":["再","见","！"],"char_start_times_ms":[500,600,700],"char_durations_ms":[100,100,0]}"""))
        val text = "[softly]你好。\n再见！"
        assertEquals("[softly]你好。\n".length, alignment.visibleTextLength(text, 200))
        assertEquals(text.length, alignment.visibleTextLength(text, 500))
        val ranges = alignment.ranges(text)!!
        assertEquals(listOf(0L, 500L), ranges.map { it.startPositionMs })
        assertEquals(listOf(160L, 700L), ranges.map { it.endPositionMs })
        assertNull(alignment.ranges("另一个句子。"))
        assertFalse(alignment.append("""{"chars":["错","误"],"char_start_times_ms":[2,1],"char_durations_ms":[1,1]}"""))
        assertEquals(ranges, alignment.ranges(text))
    }

    @Test
    fun legacyWholeReplyAudioKeepsSentenceBubblesAndOneWholeReplyControl() {
        val message = UIMessage.assistant("你好。再见！")
        val audio = VoiceCallAudioSegment("你好。再见！", "file:///voice.mp3", "MP3")
        val items = message.voiceCallDisplayItems(
            audioSegmentsOverride = listOf(audio), currentAssistantId = null,
            textGenerationPending = false,
        )
        val voiceItems = items.filterIsInstance<VoiceCallDisplayItem.Voice>()
        assertEquals(listOf("你好。", "再见！"), voiceItems.map { it.text })
        assertTrue(voiceItems.first().isWholeReplyAudio)
        assertEquals(audio, voiceItems.first().audioSegment)
        assertEquals(1, voiceItems.count { it.audioSegment != null })
    }

    @Test
    fun liveGeneratedSentencesRemainVisibleBeforeAudioAndAfterWholeReplyCapture() {
        val message = UIMessage.assistant("你好。下一句还在生成")
        val generating = message.voiceCallDisplayItems(
            currentAssistantId = message.id.toString(), textGenerationPending = true,
        )
        assertEquals(listOf("你好。", "下一句还在生成"), generating.filterIsInstance<VoiceCallDisplayItem.Voice>().map { it.text })
        assertEquals(VoiceCallDisplayItem.Loading, generating.last())

        val audio = VoiceCallAudioSegment(message.toText(), "file:///voice.mp3", "MP3")
        val captured = message.voiceCallDisplayItems(
            audioSegmentsOverride = listOf(audio), currentAssistantId = message.id.toString(),
            textGenerationPending = false,
        ).filterIsInstance<VoiceCallDisplayItem.Voice>()
        assertEquals(2, captured.size)
        assertTrue(captured.first().isWholeReplyAudio)
        assertEquals(1, captured.count { it.audioSegment != null })
    }
}
