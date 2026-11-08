package me.rerere.ai.util

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTimingTraceTest {
    @Test
    fun earlierAsrEventAndLaterStagesUseTheSameMonotonicOrigin() {
        val logs = mutableListOf<String>()
        val stopAt = System.nanoTime() - 30_000_000L
        val trace = GenerationTimingTrace("voice_call_turn", logs::add, startedAt = stopAt)

        trace.mark("asr_speech_stopped", atNanos = stopAt)
        trace.mark("asr_final", atNanos = stopAt + 20_000_000L)
        repeat(2) { trace.markOnce("audio_output_advancing") }

        assertEquals(3, logs.size)
        assertTrue(logs[0].contains("+0ms stage=asr_speech_stopped"))
        assertTrue(logs[1].contains("+20ms stage=asr_final"))
        assertEquals(1, logs.map { it.substringBefore(' ') }.distinct().size)
    }

    @Test
    fun logsOnlyFirstNonemptyTextPerStageWithoutRetainingOrPrintingItsContent() {
        val logs = mutableListOf<String>()
        val trace = GenerationTimingTrace("test", logs::add)
        val empty = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val content = "private text that must not enter logs"
        val reply = empty.copy(parts = listOf(UIMessagePart.Text(content)))

        trace.firstText("received", empty)
        repeat(1000) { trace.firstText("received", reply) }
        trace.firstText("published", reply)
        trace.mark("completed", reply.id.toString(), reply.textCharacterCount())

        assertEquals(3, logs.size)
        assertTrue(logs.first().contains("stage=received"))
        assertTrue(logs.all { it.contains("chars=${content.length}") })
        assertFalse(logs.any { it.contains(content) })
    }
}
