package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCallSpeechQueueTest {
    @Test
    fun replacingPendingReplyInvalidatesItsPreconnectionBeforeNewMessageExists() {
        val state = VoiceCallSpeechPlaybackState(initialReplyPending = false)
        state.beginReply()
        state.synchronizeMessage("previous-reply")
        state.revealThrough(20)
        val previousGeneration = state.replyGeneration

        // Both requests can be observed with pending=true in the same Compose frame.
        state.interruptReply("previous-reply")
        state.beginReply()

        assertTrue(state.replyPending)
        assertNotEquals(previousGeneration, state.replyGeneration)
        assertNull(state.activeMessageId())
        assertEquals(0, state.visibleTextLength)
        assertEquals(20, state.visibleTextOverride("previous-reply"))
    }

    @Test
    fun replacesOnlyTheFirstSegmentAndAppendsTheRest() {
        val queue = VoiceCallSpeechQueue()

        assertTrue(queue.flushForNextSegment())
        assertFalse(queue.flushForNextSegment())
        assertFalse(queue.flushForNextSegment())
    }
}
