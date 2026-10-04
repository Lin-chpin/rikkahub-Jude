package me.rerere.rikkahub.data.voice

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class VoiceCallSpeechSourceTest {
    @Test
    fun filtersUnknownInlineTagsForRegularV4Speech() {
        val input = VoiceCallSpeechInput(
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("[laughs]Hello [unknown]world")),
            ),
            finished = true,
            tagMode = VoiceCallAudioTagMode.REALTIME_MODEL,
            tagFormat = VoiceCallAudioTagFormat.ELEVEN_LABS_V4,
        )

        assertEquals("[laughs]Hello world", input.speechText())
    }

    @Test
    fun speechFinishesBeforePersistenceAndOldRepliesCannotOverwriteTheNextRequest() {
        val history = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("历史回复。")))
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("本轮回复。")))
        val source = VoiceCallSpeechSource()
        val job = Job()
        val first = source.begin(job, setOf(history.id), VoiceCallAudioTagMode.DISABLED, null)

        first.publish(listOf(history))
        assertNull(first.currentReply(listOf(history)))
        assertNull(source.state.value.message)
        first.publish(listOf(history, reply))
        assertEquals("本轮回复。", source.state.value.speechText().trim())
        first.finish()
        assertTrue(source.state.value.finished)
        assertTrue("Saving can still be running", job.isActive)

        val second = source.begin(Job(), setOf(history.id, reply.id), VoiceCallAudioTagMode.DISABLED, null)
        first.publishTagged(reply)
        first.finish(cancelled = true)
        assertNull(source.state.value.message)
        assertFalse(source.state.value.finished)
        second.finish(cancelled = true)
        assertTrue(source.state.value.cancelled)

        val beforeCancelledBegin = source.state.value
        val cancelledJob = Job().apply { cancel() }
        try {
            source.begin(cancelledJob, emptySet(), VoiceCallAudioTagMode.DISABLED, null)
            fail("A cancelled request must not replace a newer speech source")
        } catch (_: CancellationException) {
            assertSame(beforeCancelledBegin, source.state.value)
        }

        val third = source.begin(Job(), setOf(history.id), VoiceCallAudioTagMode.SECOND_PASS, VoiceCallAudioTagFormat.ELEVEN_LABS_V3)
        third.publish(listOf(reply))
        assertEquals("", source.state.value.speechText())
        third.finish()
        assertEquals("本轮回复。", source.state.value.speechText().trim())
    }
}
