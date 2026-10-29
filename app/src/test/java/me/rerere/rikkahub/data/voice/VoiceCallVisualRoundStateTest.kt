package me.rerere.rikkahub.data.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallVisualRoundStateTest {
    @Test
    fun `speech cancellation reuses captured parts and completed OCR`() = runBlocking {
        var ocrCalls = 0
        val prepared = requestWithImage()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            ocrCalls++
            prepared
        }
        val rounds = VoiceCallVisualRoundState()
        val id = rounds.replace(request)
        val frame = VoiceCallVisualFrame(VoiceCallVisualSource.CAMERA, byteArrayOf(1, 2, 3))
        rounds.framesCaptured(id, listOf(frame))
        rounds.captureFinished(id, prepared)

        val firstAttempt = rounds.beginSubmission(id)!!
        assertNull(rounds.beginSubmission(id))
        assertTrue(rounds.state.value.isPreparingGeneration)
        val firstParts = rounds.partsForRequest(id)
        rounds.submissionStatus(
            id,
            firstAttempt,
            VoiceCallVisualSendStatus.GENERATION_FLOW_COLLECTION_STARTED,
        )
        assertFalse(rounds.state.value.isPreparingGeneration)
        rounds.submissionStatus(id, firstAttempt, VoiceCallVisualSendStatus.CANCELLED)

        assertEquals(VoiceCallVisualRoundStatus.READY, rounds.state.value.status)
        assertFalse(rounds.state.value.generationFlowStarted)
        assertSame(prepared.parts, rounds.partsForRequest(id))

        val secondAttempt = rounds.beginSubmission(id)!!
        assertSame(firstParts, rounds.partsForRequest(id))
        assertEquals(1, ocrCalls)
        assertEquals(VoiceCallVisualRoundStatus.SENDING, rounds.state.value.status)
        assertTrue(rounds.state.value.isPreparingGeneration)
        assertArrayEquals(frame.jpeg, rounds.state.value.thumbnail?.jpeg)

        rounds.submissionStatus(id, secondAttempt, VoiceCallVisualSendStatus.RESPONSE_RECEIVED)
        assertEquals(VoiceCallVisualRoundStatus.SENT, rounds.state.value.status)
    }

    @Test
    fun `failed request keeps screenshot for another send`() = runBlocking {
        var ocrCalls = 0
        val prepared = requestWithImage()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            ocrCalls++
            prepared
        }
        val rounds = VoiceCallVisualRoundState()
        val id = rounds.replace(request)
        val frame = VoiceCallVisualFrame(VoiceCallVisualSource.SCREEN, byteArrayOf(4, 5, 6))
        rounds.framesCaptured(id, listOf(frame))
        rounds.captureFinished(id, prepared)
        val firstAttempt = rounds.beginSubmission(id)!!
        rounds.submissionStatus(id, firstAttempt, VoiceCallVisualSendStatus.FAILED)

        assertEquals(VoiceCallVisualRoundStatus.FAILED, rounds.state.value.status)
        assertTrue(rounds.state.value.failureMessage!!.contains("画面已保留"))
        assertArrayEquals(frame.jpeg, rounds.state.value.thumbnail?.jpeg)

        rounds.beginSubmission(id)
        assertSame(prepared.parts, rounds.partsForRequest(id))
        assertEquals(1, ocrCalls)
        assertFalse(rounds.state.value.completed)
    }

    @Test
    fun `capture and complete OCR failures are visible and keep any captured frame`() {
        val rounds = VoiceCallVisualRoundState()
        val noFrameId = rounds.replace(CompletableDeferred())
        val noFrameAttempt = rounds.beginSubmission(noFrameId)!!
        rounds.captureFinished(
            noFrameId,
            VoiceCallVisualRequest(emptyList(), hadActiveSource = true, capturedFrameCount = 0, failedOcrCount = 0),
        )
        assertEquals(VoiceCallVisualRoundStatus.FAILED, rounds.state.value.status)
        assertTrue(rounds.state.value.failureMessage!!.contains("截图失败"))
        rounds.submissionStatus(noFrameId, noFrameAttempt, VoiceCallVisualSendStatus.RESPONSE_RECEIVED)
        rounds.submissionStatus(noFrameId, noFrameAttempt, VoiceCallVisualSendStatus.COMPLETED)
        assertEquals(VoiceCallVisualRoundStatus.FAILED, rounds.state.value.status)
        assertTrue(rounds.beginSubmission(noFrameId) != null)

        val ocrFailureId = rounds.replace(CompletableDeferred())
        val frame = VoiceCallVisualFrame(VoiceCallVisualSource.SCREEN, byteArrayOf(6, 7))
        rounds.framesCaptured(ocrFailureId, listOf(frame))
        val ocrFailureAttempt = rounds.beginSubmission(ocrFailureId)!!
        rounds.captureFinished(
            ocrFailureId,
            VoiceCallVisualRequest(emptyList(), hadActiveSource = true, capturedFrameCount = 1, failedOcrCount = 1),
        )
        rounds.submissionStatus(ocrFailureId, ocrFailureAttempt, VoiceCallVisualSendStatus.RESPONSE_RECEIVED)
        rounds.submissionStatus(ocrFailureId, ocrFailureAttempt, VoiceCallVisualSendStatus.COMPLETED)

        assertEquals(VoiceCallVisualRoundStatus.FAILED, rounds.state.value.status)
        assertTrue(rounds.state.value.failureMessage!!.contains("重新截图"))
        assertArrayEquals(frame.jpeg, rounds.state.value.thumbnail?.jpeg)
        assertTrue(rounds.beginSubmission(ocrFailureId) != null)
    }

    @Test
    fun `interruption while OCR runs keeps frame and reuses the same OCR result`() = runBlocking {
        var ocrCalls = 0
        val ocrResult = CompletableDeferred<VoiceCallVisualRequest>()
        val request = async(start = CoroutineStart.LAZY) {
            ocrCalls++
            ocrResult.await()
        }
        val rounds = VoiceCallVisualRoundState()
        val id = rounds.replace(request)
        val frame = VoiceCallVisualFrame(VoiceCallVisualSource.SCREEN, byteArrayOf(7, 8))
        rounds.framesCaptured(id, listOf(frame))

        val firstAttempt = rounds.beginSubmission(id)!!
        val interruptedWaiter = async(start = CoroutineStart.UNDISPATCHED) { rounds.partsForRequest(id) }
        rounds.interruptSubmission(id)
        interruptedWaiter.cancelAndJoin()

        assertEquals(VoiceCallVisualRoundStatus.READY, rounds.state.value.status)
        assertArrayEquals(frame.jpeg, rounds.state.value.thumbnail?.jpeg)
        val retryAttempt = rounds.beginSubmission(id)!!
        ocrResult.complete(requestWithImage())

        assertEquals(requestWithImage().parts, rounds.partsForRequest(id))
        assertEquals(1, ocrCalls)
        assertEquals(VoiceCallVisualRoundStatus.SENDING, rounds.state.value.status)
        rounds.submissionStatus(id, retryAttempt, VoiceCallVisualSendStatus.CANCELLED)
        rounds.submissionStatus(id, firstAttempt, VoiceCallVisualSendStatus.RESPONSE_RECEIVED)
        assertEquals(VoiceCallVisualRoundStatus.READY, rounds.state.value.status)
    }

    @Test
    fun `replacing screenshot ignores completion from prior round`() = runBlocking {
        val rounds = VoiceCallVisualRoundState()
        val oldRequest = CompletableDeferred<VoiceCallVisualRequest>()
        val oldId = rounds.replace(oldRequest)
        val currentRequest = CompletableDeferred<VoiceCallVisualRequest>()
        val currentId = rounds.replace(currentRequest)
        val currentFrame = VoiceCallVisualFrame(VoiceCallVisualSource.SCREEN, byteArrayOf(9))

        rounds.framesCaptured(oldId, listOf(VoiceCallVisualFrame(VoiceCallVisualSource.CAMERA, byteArrayOf(1))))
        rounds.framesCaptured(currentId, listOf(currentFrame))
        val currentAttempt = rounds.beginSubmission(currentId)!!
        rounds.submissionStatus(oldId, currentAttempt, VoiceCallVisualSendStatus.COMPLETED)

        assertEquals(currentId, rounds.state.value.sessionId)
        assertEquals(VoiceCallVisualRoundStatus.SENDING, rounds.state.value.status)
        assertArrayEquals(currentFrame.jpeg, rounds.state.value.thumbnail?.jpeg)

        rounds.submissionStatus(currentId, currentAttempt, VoiceCallVisualSendStatus.COMPLETED)
        assertTrue(rounds.state.value.completed)
        assertEquals(currentId, rounds.currentSessionId())
        assertArrayEquals(currentFrame.jpeg, rounds.state.value.thumbnail?.jpeg)
        assertNull(rounds.beginSubmission(currentId))

        rounds.interruptSubmission(currentId)
        assertFalse(rounds.state.value.completed)
        assertEquals(VoiceCallVisualRoundStatus.READY, rounds.state.value.status)
        assertEquals(currentId, rounds.currentSessionId())
        assertArrayEquals(currentFrame.jpeg, rounds.state.value.thumbnail?.jpeg)

        val retryAttempt = rounds.beginSubmission(currentId)!!
        currentRequest.complete(requestWithImage())
        assertEquals(requestWithImage().parts, rounds.partsForRequest(currentId))
        rounds.submissionStatus(currentId, retryAttempt, VoiceCallVisualSendStatus.COMPLETED)
        assertTrue(rounds.state.value.completed)
        rounds.finishReply(currentId)
        assertNull(rounds.currentSessionId())
        assertNull(rounds.state.value.thumbnail)
        rounds.clear(currentId)
        assertEquals(null, rounds.state.value.sessionId)
    }

    private fun requestWithImage() = VoiceCallVisualRequest(
        parts = listOf(UIMessagePart.Text("[识图结果] 屏幕上有一个标题。")),
        hadActiveSource = true,
        capturedFrameCount = 1,
        failedOcrCount = 0,
    )
}
