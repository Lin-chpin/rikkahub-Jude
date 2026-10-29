package me.rerere.rikkahub.data.voice

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID

internal enum class VoiceCallVisualRoundStatus { CAPTURING, READY, SENDING, SENT, FAILED }

enum class VoiceCallVisualSendStatus {
    GENERATION_FLOW_COLLECTION_STARTED, RESPONSE_RECEIVED, COMPLETED, FAILED, CANCELLED
}

internal data class VoiceCallVisualRoundSnapshot(
    val sessionId: String? = null,
    val status: VoiceCallVisualRoundStatus? = null,
    val thumbnail: VoiceCallVisualFrame? = null,
    val isCapturing: Boolean = false,
    val isRecognizing: Boolean = false,
    val generationFlowStarted: Boolean = false,
    val completed: Boolean = false,
    val failureMessage: String? = null,
) {
    val isPreparingGeneration: Boolean
        get() = status == VoiceCallVisualRoundStatus.SENDING && !generationFlowStarted
}

/** Keeps a captured frame and its OCR result only for the active voice-call round. */
internal class VoiceCallVisualRoundState {
    private data class Round(
        val id: String,
        val request: Deferred<VoiceCallVisualRequest>,
        var sendAttemptId: String? = null,
    )

    private var round: Round? = null
    private val mutableState = MutableStateFlow(VoiceCallVisualRoundSnapshot())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun replace(request: Deferred<VoiceCallVisualRequest>): String =
        replace(UUID.randomUUID().toString(), request)

    @Synchronized
    fun replace(sessionId: String, request: Deferred<VoiceCallVisualRequest>): String {
        val oldRequest = round?.request
        round = Round(sessionId, request)
        mutableState.value = VoiceCallVisualRoundSnapshot(
            sessionId = sessionId,
            status = VoiceCallVisualRoundStatus.CAPTURING,
            isCapturing = true,
        )
        oldRequest?.cancel()
        return sessionId
    }

    @Synchronized
    fun currentSessionId(): String? = round?.id

    @Synchronized
    fun isCurrent(sessionId: String): Boolean = round?.id == sessionId

    @Synchronized
    fun framesCaptured(sessionId: String, frames: List<VoiceCallVisualFrame>) {
        if (round?.id != sessionId) return
        val frame = frames.firstOrNull()
        mutableState.update { current ->
            current.copy(
                status = if (frame == null) current.status else current.statusAfterFrameReady(),
                thumbnail = frame,
                isCapturing = false,
                failureMessage = if (frame == null) "截图未就绪，请重新截屏。" else current.failureMessage,
            )
        }
    }

    @Synchronized
    fun recognitionChanged(sessionId: String, recognizing: Boolean) {
        if (round?.id != sessionId) return
        mutableState.update { it.copy(isRecognizing = recognizing) }
    }

    @Synchronized
    fun captureFailed(sessionId: String) {
        if (round?.id != sessionId) return
        mutableState.update {
            it.copy(
                status = VoiceCallVisualRoundStatus.FAILED,
                isCapturing = false,
                isRecognizing = false,
                failureMessage = "截图失败，请重新截屏。",
            )
        }
    }

    @Synchronized
    fun captureFinished(sessionId: String, request: VoiceCallVisualRequest) {
        if (round?.id != sessionId) return
        val attemptInProgress = round?.sendAttemptId != null
        val failure = when {
            request.capturedFrameCount == 0 -> "截图失败，请重新截屏。"
            request.failedOcrCount == request.capturedFrameCount && request.parts.isEmpty() ->
                "识图失败，画面已保留；重新截图可重试。"
            else -> null
        }
        mutableState.update { current ->
            current.copy(
                status = when {
                    failure != null -> VoiceCallVisualRoundStatus.FAILED
                    attemptInProgress -> current.status
                    else -> VoiceCallVisualRoundStatus.READY
                },
                isCapturing = false,
                isRecognizing = false,
                failureMessage = failure,
            )
        }
    }

    suspend fun preparedRequest(sessionId: String): VoiceCallVisualRequest? {
        val request = synchronized(this) { round?.takeIf { it.id == sessionId }?.request }
            ?: return null
        val prepared = request.await()
        val stillCurrent = synchronized(this) {
            if (round?.id != sessionId) return@synchronized false
            captureFinished(sessionId, prepared)
            true
        }
        return if (stillCurrent) prepared else null
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
                        failureMessage = if (it.thumbnail != null) {
                            "发送失败，画面已保留；再说一次可重试。"
                        } else {
                            "发送失败，截图未就绪；请重新截屏。"
                        },
                    )
                }
            }
            VoiceCallVisualSendStatus.CANCELLED -> {
                currentRound.sendAttemptId = null
                mutableState.update {
                    if (it.status == VoiceCallVisualRoundStatus.FAILED) it
                    else it.copy(
                        status = if (it.thumbnail != null) VoiceCallVisualRoundStatus.READY
                        else if (it.isCapturing) VoiceCallVisualRoundStatus.CAPTURING
                        else VoiceCallVisualRoundStatus.FAILED,
                        generationFlowStarted = false,
                        completed = false,
                        failureMessage = if (it.thumbnail != null || it.isCapturing) null
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
        current.request.cancel()
        round = null
        mutableState.update {
            if (it.sessionId != sessionId) it
            else it.copy(thumbnail = null, isCapturing = false, isRecognizing = false)
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
        current?.request?.cancel()
        mutableState.value = VoiceCallVisualRoundSnapshot()
    }

    private fun VoiceCallVisualRoundSnapshot.statusAfterFrameReady(): VoiceCallVisualRoundStatus =
        if (status == VoiceCallVisualRoundStatus.SENDING || status == VoiceCallVisualRoundStatus.SENT) status
        else VoiceCallVisualRoundStatus.READY
}
