package me.rerere.rikkahub.data.voice

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.beginAssistantResponse
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceCallSpeechSourceTest {
    @Test fun cancellationRejectsLateTokensEvenBeforeTheFinishCallbackRuns() {
        val source = VoiceCallSpeechSource()
        val job = Job()
        val turn = source.begin(job, emptySet(), VoiceCallAudioTagMode.DISABLED, null)
        val spoken = UIMessage.assistant("已经生成。")
        turn.publish(listOf(spoken))
        job.cancel()
        turn.publish(listOf(spoken.copy(parts = listOf(UIMessagePart.Text("迟到内容。")))))
        turn.publishTagged(spoken.copy(parts = listOf(UIMessagePart.Text("迟到标注。"))))
        assertEquals("已经生成。", source.state.value.message?.toText()?.trim())
        turn.finish(cancelled = true)
        assertTrue(source.state.value.cancelled)
    }

    @Test
    fun acceptedCallStartsNewSpeechMessageForStreamingAndCompleteResponses() {
        val invitation = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("来电前的邀请。"),
                UIMessagePart.Tool(
                    toolCallId = "incoming-call",
                    toolName = "voice_call",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("connected")),
                    approvalState = ToolApprovalState.Approved,
                ),
            ),
        )
        val history = listOf(invitation)
        val modelId = Uuid.random()
        assertSame(history, beginAssistantResponse(history, modelId, startNewMessage = false))

        for (streaming in listOf(true, false)) {
            val responseMessages = beginAssistantResponse(history, modelId, startNewMessage = true)
            assertSame(invitation, responseMessages.first())
            assertNotEquals(invitation.id, responseMessages.last().id)
            val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("接通后的回复。")))
            val generated = responseMessages.handleMessageChunk(
                MessageChunk(
                    id = "response",
                    model = "model",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = reply.takeIf { streaming },
                            message = reply.takeUnless { streaming },
                            finishReason = "stop",
                        ),
                    ),
                ),
            )
            val source = VoiceCallSpeechSource()
            val speech = source.begin(Job(), setOf(invitation.id), VoiceCallAudioTagMode.DISABLED, null)
            speech.publish(generated)
            speech.finish()
            assertEquals("接通后的回复。", source.state.value.speechText().trim())
            assertEquals(responseMessages.last().id, source.state.value.message?.id)
            assertEquals("来电前的邀请。", generated.first().toText().trim())
        }
    }

    @Test
    fun preservesFlexibleInlineTagsForElevenLabsRealtimeSpeech() {
        listOf(VoiceCallAudioTagFormat.ELEVEN_LABS_V3, VoiceCallAudioTagFormat.ELEVEN_LABS_V4)
            .forEach { format ->
                val script = "[annoyed, but amused]Hello [quietly, pretending not to care]world"
                val input = VoiceCallSpeechInput(
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(script)),
                    ),
                    finished = true,
                    tagMode = VoiceCallAudioTagMode.REALTIME_MODEL,
                    tagFormat = format,
                )

                assertEquals(script, input.speechText())
            }
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
