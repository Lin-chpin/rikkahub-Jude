package me.rerere.rikkahub.data.voice

import me.rerere.asr.ASRUtterance
import org.junit.Assert.*
import org.junit.Test

class VoiceCallUtteranceGateTest {
    @Test fun olderFinalTranscriptCannotInterruptANewerUtterance() {
        val gate = VoiceCallUtteranceGate()
        gate.accept(ASRUtterance("old", "", false))
        gate.accept(ASRUtterance("new", "", false))
        assertTrue(gate.accept(ASRUtterance("new", "换个话题", false))!!.interrupt)
        assertNull(gate.accept(ASRUtterance("old", "迟到的上一句", true)))
        assertEquals("换个话题", gate.accept(ASRUtterance("new", "换个话题", true))?.submittedText)
    }

    @Test fun ignoresNoiseAndFillersButInterruptsBeforeFinalAndSubmitsOnlyOnce() {
        val gate = VoiceCallUtteranceGate()
        listOf("", "...", "嗯", "嗯嗯", "啊", "um", "hmm").forEachIndexed { index, text ->
            assertEquals(VoiceCallInputAction(false, null), gate.accept(ASRUtterance("noise" + index, text, true)))
        }
        assertEquals(VoiceCallInputAction(true, null), gate.accept(ASRUtterance("a", "等一下", false)))
        assertEquals(VoiceCallInputAction(false, null), gate.accept(ASRUtterance("a", "等一下我想", false)))
        assertEquals(VoiceCallInputAction(false, "等一下我想换个话题"), gate.accept(ASRUtterance("a", "等一下我想换个话题", true)))
        assertNull(gate.accept(ASRUtterance("a", "迟到的重复结果", true)))
        assertEquals(VoiceCallInputAction(true, "停"), gate.accept(ASRUtterance("b", "停", true)))
    }

    @Test fun repeatedWordsInDifferentUtterancesAreIndependentTurns() {
        val gate = VoiceCallUtteranceGate()
        assertEquals("你好", gate.accept(ASRUtterance("first", "你好", true))?.submittedText)
        assertEquals("你好", gate.accept(ASRUtterance("second", "你好", true))?.submittedText)
    }
}
