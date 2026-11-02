package me.rerere.rikkahub.data.voice

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID

internal const val VOICE_CALL_VISUAL_MAX_IMAGES = 10

internal enum class VoiceCallVisualRoundStatus { CAPTURING, READY, SENDING, SENT, FAILED }

enum class VoiceCallVisualSendStatus {
    GENERATION_FLOW_COLLECTION_STARTED, RESPONSE_RECEIVED, COMPLETED, FAILED, CANCELLED
}

internal data class VoiceCallVisualRoundSnapshot(
    val sessionId: String? = null,
    val status: VoiceCallVisualRoundStatus? = null,
    val isCapturing: Boolean = false,
    val isRecognizing: Boolean = false,
    val generationFlowStarted: Boolean = false,
    val completed: Boolean = false,
    val failureMessage: String? = null,
    val capturedImageCount: Int = 0,
    val recognizedImageCount: Int = 0,
    val failedImageCount: Int = 0,
) {
    val isPreparingGeneration: Boolean
        get() = status == VoiceCallVisualRoundStatus.SENDING && !generationFlowStarted

    val canAddScreenshot: Boolean
        get() = !isCapturing && (completed ||
            (status != VoiceCallVisualRoundStatus.SENDING && status != VoiceCallVisualRoundStatus.SENT &&
                capturedImageCount < VOICE_CALL_VISUAL_MAX_IMAGES))

    val allImagesFailedRecognition: Boolean
        get() = capturedImageCount > 0 && !isRecognizing && recognizedImageCount == 0 &&
            failedImageCount >= capturedImageCount
}

/** Accumulates recognition results for one call turn and never retains captured image bytes. */
internal class VoiceCallVisualRoundState {
    private data class Round(
        val id: String,
        val requests: MutableList<Deferred<VoiceCallVisualRequest>> = mutableListOf(),
        var sendAttemptId: String? = null,
        var recognitionJobs: Int = 0,
    )

    private var round: Round? = null
    private val mutableState = MutableStateFlow(VoiceCallVisualRoundSnapshot())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun replace(request: Deferred<VoiceCallVisualRequest>): String =
        replace(UUID.randomUUID().toString(), request)

    @Synchronized
    fun replace(sessionId: String, request: Deferred<VoiceCallVisualRequest>): String {
        round?.requests?.forEach { it.cancel() }
        round = Round(sessionId, mutableListOf(request))
        mutableState.value = VoiceCallVisualRoundSnapshot(
            sessionId = sessionId,
            status = VoiceCallVisualRoundStatus.CAPTURING,
            isCapturing = true,
        )
        return sessionId
    }

    @Synchronized
    fun beginCapture(): String? {
        var current = round
        if (current == null || mutableState.value.completed) {
            current?.requests?.forEach { it.cancel() }
            current = Round(UUID.randomUUID().toString())
            round = current
            mutableState.value = VoiceCallVisualRoundSnapshot(
                sessionId = current.id,
                status = VoiceCallVisualRoundStatus.CAPTURING,
                isCapturing = true,
            )
            return current.id
        }
        val snapshot = mutableState.value
        if (current.sendAttemptId != null || snapshot.isCapturing ||
            snapshot.status == VoiceCallVisualRoundStatus.SENT ||
            snapshot.capturedImageCount >= VOICE_CALL_VISUAL_MAX_IMAGES
        ) return null
        mutableState.update {
            it.copy(
                status = VoiceCallVisualRoundStatus.CAPTURING,
                isCapturing = true,
                failureMessage = null,
            )
        }
        return current.id
    }

    @Synchronized
    fun remainingImageCount(sessionId: String): Int =
        if (round?.id == sessionId) {
            (VOICE_CALL_VISUAL_MAX_IMAGES - mutableState.value.capturedImageCount).coerceAtLeast(0)
        } else 0

    @Synchronized
    fun addCaptureRequest(sessionId: String, request: Deferred<VoiceCallVisualRequest>) {
        val current = round?.takeIf { it.id == sessionId } ?: run {
            request.cancel()
            return
        }
        current.requests += request
    }

    @Synchronized
    fun currentSessionId(): String? = round?.id

    @Synchronized
    fun isCurrent(sessionId: String): Boolean = round?.id == sessionId

    @Synchronized
    fun framesCaptured(sessionId: String, frames: List<VoiceCallVisualFrame>) {
        if (round?.id != sessionId) return
        val count = minOf(
            frames.size,
            VOICE_CALL_VISUAL_MAX_IMAGES - mutableState.value.capturedImageCount,
        ).coerceAtLeast(0)
        mutableState.update { current ->
            current.copy(
                status = if (count == 0) current.status else current.statusAfterFrameReady(),
                isCapturing = false,
                capturedImageCount = current.capturedImageCount + count,
                failureMessage = if (count == 0 && current.capturedImageCount == 0) "截图未就绪，请重新截屏。" else current.failureMessage,
            )
        }
    }

    @Synchronized
    fun recognitionChanged(sessionId: String, recognizing: Boolean) {
        val current = round?.takeIf { it.id == sessionId } ?: return
        current.recognitionJobs = (current.recognitionJobs + if (recognizing) 1 else -1).coerceAtLeast(0)
        mutableState.update { it.copy(isRecognizing = current.recognitionJobs > 0) }
    }

    @Synchronized
    fun captureFailed(sessionId: String) {
        if (round?.id != sessionId) return
        mutableState.update {
            it.copy(
                status = if (it.capturedImageCount > 0) VoiceCallVisualRoundStatus.READY else VoiceCallVisualRoundStatus.FAILED,
                isCapturing = false,
                isRecognizing = false,
                failureMessage = if (it.capturedImageCount > 0) "新增截图失败，已有识图结果仍可发送。" else "截图失败，请重新截屏。",
            )
        }
    }

    @Synchronized
    fun captureFinished(sessionId: String, request: VoiceCallVisualRequest) {
        if (round?.id != sessionId) return
        val currentRound = round ?: return
        val attemptInProgress = currentRound.sendAttemptId != null
        val current = mutableState.value
        val requestRecognizedCount = request.recognizedDescriptions.size.takeIf { it > 0 }
            ?: if (request.parts.isNotEmpty()) {
                (request.capturedFrameCount - request.failedOcrCount).coerceAtLeast(0)
            } else 0
        val recognizedCount = current.recognizedImageCount + requestRecognizedCount
        val failedCount = current.failedImageCount + request.failedOcrCount
        val noImages = current.capturedImageCount == 0 && request.capturedFrameCount == 0
        val allRecognitionFailed = current.capturedImageCount > 0 && recognizedCount == 0 &&
            failedCount >= current.capturedImageCount
        val failure = when {
            noImages -> "截图失败，请重新截屏。"
            allRecognitionFailed -> "识图失败，原图未保留；请重新截屏。"
            else -> null
        }
        mutableState.update {
            it.copy(
                status = when {
                    attemptInProgress -> it.status
                    failure != null -> VoiceCallVisualRoundStatus.FAILED
                    else -> VoiceCallVisualRoundStatus.READY
                },
                isCapturing = false,
                isRecognizing = currentRound.recognitionJobs > 0,
                recognizedImageCount = recognizedCount,
                failedImageCount = failedCount,
                failureMessage = failure,
            )
        }
    }

    suspend fun preparedRequest(sessionId: String): VoiceCallVisualRequest? {
        val requests = synchronized(this) {
            round?.takeIf { it.id == sessionId }?.requests?.toList()
        } ?: return null
        val preparedRequests = requests.awaitAll()
        return synchronized(this) {
            if (round?.id != sessionId) return@synchronized null
            if (preparedRequests.size == 1) return@synchronized preparedRequests.single()
            val descriptions = preparedRequests.flatMap { it.recognizedDescriptions }
            val fallbackParts = preparedRequests.flatMap { it.parts }
            val combined = VoiceCallVisualRequest(
                parts = if (descriptions.isNotEmpty()) voiceCallVisualRequestParts(descriptions) else fallbackParts,
                hadActiveSource = preparedRequests.any { it.hadActiveSource },
                capturedFrameCount = preparedRequests.sumOf { it.capturedFrameCount },
                failedOcrCount = preparedRequests.sumOf { it.failedOcrCount },
                recognizedDescriptions = descriptions,
            )
            combined
        }
    }

    suspend fun partsForRequest(sessionId: String): List<UIMessagePart> =
        preparedRequest(sessionId)?.parts.orEmpty()

    @Synchronized
    fun beginSubmission(sessionId: String): String? {
        val currentRound = round?.takeIf { it.id == sessionId } ?: return null
        if (currentRound.sendAttemptId != null || mutableState.value.completed) return null
        val attemptId = UUID.randomUUID().toString()
        currentRound.sendAttemptId = attemptId
        mutableState.update {
            it.copy(
                status = VoiceCallVisualRoundStatus.SENDING,
                generationFlowStarted = false,
                completed = false,
                failureMessage = null,
            )
        }
        return attemptId
    }

    @Synchronized
    fun submissionStatus(sessionId: String, attemptId: String, status: VoiceCallVisualSendStatus) {
        val currentRound = round?.takeIf { it.id == sessionId } ?: return
        if (currentRound.sendAttemptId != attemptId) return
        when (status) {
            VoiceCallVisualSendStatus.GENERATION_FLOW_COLLECTION_STARTED -> {
                mutableState.update { it.copy(generationFlowStarted = true) }
            }
            VoiceCallVisualSendStatus.RESPONSE_RECEIVED -> {
                mutableState.update {
                    if (it.status == VoiceCallVisualRoundStatus.FAILED) it
                    else it.copy(status = VoiceCallVisualRoundStatus.SENT)
                }
            }
            VoiceCallVisualSendStatus.COMPLETED -> {
                currentRound.sendAttemptId = null
                if (mutableState.value.status == VoiceCallVisualRoundStatus.FAILED) return
                mutableState.update {
                    it.copy(
                        status = VoiceCallVisualRoundStatus.SENT,
                        isCapturing = false,
                        isRecognizing = false,
                        completed = true,
                    )
                }
            }
            VoiceCallVisualSendStatus.FAILED -> {
                currentRound.sendAttemptId = null
                mutableState.update {
                    it.copy(
                        status = VoiceCallVisualRoundStatus.FAILED,
                        generationFlowStarted = false,
                        completed = false,
                        failureMessage = if (it.recognizedImageCount > 0) {
                            "发送失败，识图文字仍可保留重试。"
                        } else {
                            "发送失败，未获得可用的画面识别结果。"
                        },
                    )
                }
            }
            VoiceCallVisualSendStatus.CANCELLED -> {
                currentRound.sendAttemptId = null
                mutableState.update {
                    if (it.status == VoiceCallVisualRoundStatus.FAILED) it
                    else it.copy(
                        status = if (it.recognizedImageCount > 0) VoiceCallVisualRoundStatus.READY
                        else if (it.isCapturing) VoiceCallVisualRoundStatus.CAPTURING
                        else if (it.isRecognizing) VoiceCallVisualRoundStatus.READY
                        else VoiceCallVisualRoundStatus.FAILED,
                        generationFlowStarted = false,
                        completed = false,
                        failureMessage = if (it.recognizedImageCount > 0 || it.isCapturing || it.isRecognizing) null
                        else it.failureMessage,
                    )
                }
            }
        }
    }

    @Synchronized
    fun finishReply(sessionId: String) {
        val current = round?.takeIf { it.id == sessionId } ?: return
        if (!mutableState.value.completed || current.sendAttemptId != null) return
        current.requests.forEach { it.cancel() }
        round = null
        mutableState.update {
            if (it.sessionId != sessionId) it
            else it.copy(isCapturing = false, isRecognizing = false)
        }
    }

    @Synchronized
    fun interruptSubmission(sessionId: String? = round?.id) {
        val current = round?.takeIf { it.id == sessionId } ?: return
        val attemptId = current.sendAttemptId
        if (attemptId == null) {
            if (mutableState.value.completed) {
                mutableState.update {
                    if (it.sessionId != sessionId) it
                    else it.copy(status = VoiceCallVisualRoundStatus.READY, completed = false)
                }
            }
            return
        }
        submissionStatus(current.id, attemptId, VoiceCallVisualSendStatus.CANCELLED)
    }

    @Synchronized
    fun clear(sessionId: String? = round?.id) {
        val current = round?.takeIf { it.id == sessionId }
        if (current == null && mutableState.value.sessionId != sessionId) return
        round = null
        current?.requests?.forEach { it.cancel() }
        mutableState.value = VoiceCallVisualRoundSnapshot()
    }

    private fun VoiceCallVisualRoundSnapshot.statusAfterFrameReady(): VoiceCallVisualRoundStatus =
        if (status == VoiceCallVisualRoundStatus.SENDING || status == VoiceCallVisualRoundStatus.SENT) status
        else VoiceCallVisualRoundStatus.READY
}
