package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallSpeechQueueTest {
    @Test
    fun replacesOnlyTheFirstSegmentAndAppendsTheRest() {
        val queue = VoiceCallSpeechQueue()

        assertTrue(queue.flushForNextSegment())
        assertFalse(queue.flushForNextSegment())
        assertFalse(queue.flushForNextSegment())
    }
}
