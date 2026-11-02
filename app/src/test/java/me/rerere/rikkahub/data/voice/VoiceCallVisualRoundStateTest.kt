package me.rerere.rikkahub.data.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallVisualRoundStateTest {
    @Test
    fun `screenshot count is capped at ten per round`() {
        val rounds = VoiceCallVisualRoundState()
        val id = rounds.beginCapture()!!

        rounds.framesCaptured(
            id,
            List(VOICE_CALL_VISUAL_MAX_IMAGES + 1) {
                VoiceCallVisualFrame(VoiceCallVisualSource.CAMERA, byteArrayOf(it.toByte()))
            },
        )

        assertEquals(VOICE_CALL_VISUAL_MAX_IMAGES, rounds.state.value.capturedImageCount)
        assertFalse(rounds.state.value.canAddScreenshot)
        assertNull(rounds.beginCapture())
    }

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
        assertEquals(1, rounds.state.value.capturedImageCount)

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
        assertTrue(rounds.state.value.failureMessage!!.contains("识图文字仍可保留"))
        assertEquals(1, rounds.state.value.capturedImageCount)

        rounds.beginSubmission(id)
        assertSame(prepared.parts, rounds.partsForRequest(id))
        assertEquals(1, ocrCalls)
        assertFalse(rounds.state.value.completed)
    }

    @Test
    fun `capture and OCR failures remain visible while voice submission completes`() {
        val rounds = VoiceCallVisualRoundState()
        val noFrameId = rounds.replace(CompletableDeferred())
        val noFrameAttempt = rounds.beginSubmission(noFrameId)!!
        rounds.captureFinished(
            noFrameId,
            VoiceCallVisualRequest(emptyList(), hadActiveSource = true, capturedFrameCount = 0, failedOcrCount = 0),
        )
        assertEquals(VoiceCallVisualRoundStatus.SENDING, rounds.state.value.status)
        assertTrue(rounds.state.value.failureMessage!!.contains("截图失败"))
        rounds.submissionStatus(noFrameId, noFrameAttempt, VoiceCallVisualSendStatus.RESPONSE_RECEIVED)
        rounds.submissionStatus(noFrameId, noFrameAttempt, VoiceCallVisualSendStatus.COMPLETED)
        assertEquals(VoiceCallVisualRoundStatus.SENT, rounds.state.value.status)
        assertNull(rounds.beginSubmission(noFrameId))

        val ocrFailureId = rounds.replace(CompletableDeferred())
        val frame = VoiceCallVisualFrame(VoiceCallVisualSource.SCREEN, byteArrayOf(6, 7))
        rounds.framesCaptured(ocrFailureId, listOf(frame))
        val ocrFailureAttempt = rounds.beginSubmission(ocrFailureId)!!
        rounds.captureFinished(
            ocrFailureId,
            VoiceCallVisualRequest(emptyList(), hadActiveSource = true, capturedFrameCount = 1, failedOcrCount = 1),
        )
        assertEquals(VoiceCallVisualRoundStatus.SENDING, rounds.state.value.status)
        assertTrue(rounds.state.value.allImagesFailedRecognition)
        assertTrue(rounds.state.value.failureMessage!!.contains("原图未保留"))
        rounds.submissionStatus(ocrFailureId, ocrFailureAttempt, VoiceCallVisualSendStatus.RESPONSE_RECEIVED)
        rounds.submissionStatus(ocrFailureId, ocrFailureAttempt, VoiceCallVisualSendStatus.COMPLETED)

        assertEquals(VoiceCallVisualRoundStatus.SENT, rounds.state.value.status)
        assertTrue(rounds.state.value.failureMessage!!.contains("原图未保留"))
        assertEquals(1, rounds.state.value.capturedImageCount)
        assertNull(rounds.beginSubmission(ocrFailureId))
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
        rounds.recognitionChanged(id, true)

        val firstAttempt = rounds.beginSubmission(id)!!
        val interruptedWaiter = async(start = CoroutineStart.UNDISPATCHED) { rounds.partsForRequest(id) }
        rounds.interruptSubmission(id)
        interruptedWaiter.cancelAndJoin()

        assertEquals(VoiceCallVisualRoundStatus.READY, rounds.state.value.status)
        assertEquals(1, rounds.state.value.capturedImageCount)
        val retryAttempt = rounds.beginSubmission(id)!!
        val prepared = requestWithImage()
        ocrResult.complete(prepared)

        assertEquals(prepared.parts, rounds.partsForRequest(id))
        rounds.recognitionChanged(id, false)
        rounds.captureFinished(id, prepared)
        assertEquals(1, ocrCalls)
        assertEquals(1, rounds.state.value.recognizedImageCount)
        assertFalse(rounds.state.value.isRecognizing)
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
        assertEquals(1, rounds.state.value.capturedImageCount)

        rounds.submissionStatus(currentId, currentAttempt, VoiceCallVisualSendStatus.COMPLETED)
        assertTrue(rounds.state.value.completed)
        assertEquals(currentId, rounds.currentSessionId())
        assertEquals(1, rounds.state.value.capturedImageCount)
        assertNull(rounds.beginSubmission(currentId))

        rounds.interruptSubmission(currentId)
        assertFalse(rounds.state.value.completed)
        assertEquals(VoiceCallVisualRoundStatus.READY, rounds.state.value.status)
        assertEquals(currentId, rounds.currentSessionId())
        assertEquals(1, rounds.state.value.capturedImageCount)

        val retryAttempt = rounds.beginSubmission(currentId)!!
        currentRequest.complete(requestWithImage())
        assertEquals(requestWithImage().parts, rounds.partsForRequest(currentId))
        rounds.submissionStatus(currentId, retryAttempt, VoiceCallVisualSendStatus.COMPLETED)
        assertTrue(rounds.state.value.completed)
        rounds.finishReply(currentId)
        assertNull(rounds.currentSessionId())
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
