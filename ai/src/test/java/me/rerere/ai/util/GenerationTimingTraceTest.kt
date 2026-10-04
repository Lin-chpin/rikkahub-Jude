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
