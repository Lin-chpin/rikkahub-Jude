package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.VoiceCallAudioSegment
import me.rerere.ai.util.GenerationTimingTrace
import me.rerere.rikkahub.data.voice.VoiceCallSpeechInput
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.service.sanitizeVoiceCallTextForSpeech
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackStatus

/**
 * Owns live-call speech playback progress and persisted audio segments.
 *
 * The call screen consumes this state but does not manage synthesis queues or
 * playback timing. Changing TTS behavior should stay in this file so keyboard,
 * dialog, and hang-up lifecycles remain isolated from speech-provider changes.
 */
@Stable
internal class VoiceCallSpeechPlaybackState(initialReplyPending: Boolean) {
    var replyPending by mutableStateOf(initialReplyPending)
        private set
    var replyGeneration by mutableStateOf(0L)
        private set
    var visibleTextLength by mutableStateOf(0)
        private set

    private var spokenMessageId by mutableStateOf<String?>(null)
    private var queuedTextLength by mutableStateOf(0)
    private var queuedSpeechTextLength by mutableStateOf(0)
    private var queuedIncludesUnfinishedTail by mutableStateOf(false)
    private val queuedSpeechSegments = mutableStateListOf<VoiceCallSpeechSegment>()
    private val interruptedVisibleTextLengths = mutableStateMapOf<String, Int>()
    private val audioSegmentsByMessageId = mutableStateMapOf<String, List<VoiceCallAudioSegment>>()

    fun beginReply() {
        replyGeneration++
        replyPending = true
        resetProgress(null)
    }

    fun stopReply() {
        replyPending = false
    }

    fun interruptReply(messageId: String?) {
        messageId?.let { interruptedVisibleTextLengths[it] = visibleTextLength }
        replyPending = false
        resetProgress(null)
    }

    fun isReplyActive(
        loadingJob: Job?,
        playbackStatus: PlaybackStatus,
        currentAssistantId: String?,
        currentAssistantTextLength: Int,
    ): Boolean {
        val playbackActive = playbackStatus == PlaybackStatus.Playing ||
            playbackStatus == PlaybackStatus.Buffering ||
            playbackStatus == PlaybackStatus.Paused
        return loadingJob != null ||
            playbackActive ||
            (replyPending && currentAssistantId != null && visibleTextLength < currentAssistantTextLength)
    }

    fun visibleTextOverride(messageId: String): Int? = interruptedVisibleTextLengths[messageId]

    fun audioSegments(messageId: String): List<VoiceCallAudioSegment> =
        audioSegmentsByMessageId[messageId].orEmpty()

    fun audioSegmentsSnapshot(): Map<String, List<VoiceCallAudioSegment>> =
        audioSegmentsByMessageId.toMap()

    fun audioSegmentCount(): Int = audioSegmentsByMessageId.values.sumOf { it.size }

    fun synchronizeMessage(messageId: String?) {
        if (replyPending && messageId != null && spokenMessageId != messageId) {
            resetProgress(messageId)
        }
    }

    fun queueReply(
        displayText: String,
        speechText: String,
        includeUnfinishedTail: Boolean,
    ) {
        val displaySegments = displayText.voiceCallDisplaySegments(includeUnfinishedTail)
        if (displaySegments.isEmpty()) return
        val effectiveSpeechText = speechText.ifBlank {
            displayText.takeIf { includeUnfinishedTail }.orEmpty()
        }
        val speechSegments = effectiveSpeechText
            .sanitizeVoiceCallTextForSpeech()
            .voiceCallDisplaySegments(includeUnfinishedTail)
        if (queuedTextLength == displayText.length &&
            queuedSpeechTextLength == effectiveSpeechText.length &&
            queuedIncludesUnfinishedTail == includeUnfinishedTail &&
            queuedSpeechSegments.isNotEmpty()
        ) return

        queuedTextLength = displayText.length
        queuedSpeechTextLength = effectiveSpeechText.length
        queuedIncludesUnfinishedTail = includeUnfinishedTail
        queuedSpeechSegments.clear()
        queuedSpeechSegments += displaySegments.mapIndexed { index, displaySegment ->
            VoiceCallSpeechSegment(
                text = speechSegments.getOrNull(index)?.text.orEmpty(),
                endLength = displaySegment.endLength,
            )
        }
    }

    fun activeMessageId(): String? = spokenMessageId

    fun queuedSegment(index: Int): VoiceCallSpeechSegment? = queuedSpeechSegments.getOrNull(index)

    fun canFinishReply(inputFinished: Boolean, latestAssistantText: String): Boolean {
        return inputFinished &&
            latestAssistantText.isNotBlank() &&
            queuedTextLength >= latestAssistantText.length &&
            visibleTextLength >= latestAssistantText.length
    }

    fun revealThrough(endLength: Int) {
        visibleTextLength = maxOf(visibleTextLength, endLength)
    }

    fun completeReply() {
        replyPending = false
    }

    fun appendAudioSegment(messageId: String, segment: VoiceCallAudioSegment) {
        audioSegmentsByMessageId[messageId] = audioSegmentsByMessageId[messageId].orEmpty() + segment
    }

    private fun resetProgress(messageId: String?) {
        spokenMessageId = messageId
        queuedTextLength = 0
        queuedSpeechTextLength = 0
        queuedIncludesUnfinishedTail = false
        visibleTextLength = 0
        queuedSpeechSegments.clear()
    }
}

@Composable
internal fun rememberVoiceCallSpeechPlaybackState(
    initialReplyPending: Boolean,
): VoiceCallSpeechPlaybackState = remember {
    VoiceCallSpeechPlaybackState(initialReplyPending)
}

@Composable
internal fun BindVoiceCallSpeechPlayback(
    state: VoiceCallSpeechPlaybackState,
    awaitInitialAssistantReply: Boolean,
    speechInput: StateFlow<VoiceCallSpeechInput>,
    loadingJob: Job?,
    useWholeReplyTts: Boolean,
    tts: CustomTtsState,
    filesManager: FilesManager,
    recordFlow: (String) -> Unit,
) {
    val latestLoadingJob by rememberUpdatedState(loadingJob)

    LaunchedEffect(awaitInitialAssistantReply) {
        if (awaitInitialAssistantReply && !state.replyPending) state.beginReply()
    }

    LaunchedEffect(state.replyPending, state.replyGeneration, useWholeReplyTts) {
        if (!state.replyPending) return@LaunchedEffect
        var generationJob: Job? = null
        val timing = GenerationTimingTrace("voice_call_speech_queue")
        while (state.replyPending) {
            val input = speechInput.value
            if (generationJob == null) {
                if (input.generationJob == null || !input.generationJob.isActive || input.generationJob !== latestLoadingJob) {
                    delay(20)
                    continue
                }
                generationJob = input.generationJob
            }
            if (input.generationJob !== generationJob || input.cancelled) break
            if (input.finished && input.displayText().isBlank()) {
                state.completeReply()
                break
            }
            val reply = input.message
            if (reply != null && (!useWholeReplyTts || input.finished)) {
                val displayText = input.displayText()
                val speechText = input.speechText()
                state.synchronizeMessage(reply.id.toString())
                state.queueReply(displayText, speechText, includeUnfinishedTail = input.finished)
                timing.firstText("speech_queue_ready", reply.id.toString(), speechText.length)
                if (input.finished) break
            }
            delay(20)
        }
    }

    LaunchedEffect(state.activeMessageId(), state.replyGeneration) {
        val messageId = state.activeMessageId() ?: return@LaunchedEffect
        val speechQueue = VoiceCallSpeechQueue()
        var nextSpeechIndex = 0
        while (state.activeMessageId() == messageId) {
            val input = speechInput.value
            if (input.cancelled || input.message?.id?.toString() != messageId) break
            val displayText = input.displayText()
            val speechText = input.speechText()
            val segment = state.queuedSegment(nextSpeechIndex)
            if (segment == null) {
                if (state.canFinishReply(input.finished, displayText)) {
                    state.completeReply()
                    break
                }
                delay(60)
                continue
            }

            if (segment.waitsForSpeechProjection(!input.finished)) {
                delay(60)
                continue
            }

            if (segment.text.isBlank()) {
                state.revealThrough(segment.endLength)
                nextSpeechIndex++
                continue
            }

            val ttsText = if (useWholeReplyTts) {
                speechText.sanitizeVoiceCallTextForSpeech()
            } else {
                segment.text.sanitizeVoiceCallTextForSpeech()
            }
            if (ttsText.isBlank()) {
                delay(60)
                continue
            }

            val flushCalled = speechQueue.flushForNextSegment()
            tts.speak(
                text = ttsText,
                flushCalled = flushCalled,
                chunked = false,
                emotion = null,
                onAudioReady = { response ->
                    recordFlow(
                        "收到TTS音频回调 messageId=" + messageId +
                            ", bytes=" + response.audioData.size +
                            ", format=" + response.format +
                            ", sampleRate=" + response.sampleRate
                    )
                    runCatching {
                        val file = filesManager.saveManagedFromBytes(
                            folder = FileFolders.UPLOAD,
                            bytes = response.audioData,
                            displayName = "voice-call-audio",
                            mimeType = "audio/*",
                        )
                        val audioUri = filesManager.getFile(file).toUri().toString()
                        state.appendAudioSegment(
                            messageId = messageId,
                            segment = VoiceCallAudioSegment(
                                text = ttsText,
                                audioUri = audioUri,
                                format = response.format.name,
                                sampleRate = response.sampleRate,
                            ),
                        )
                        recordFlow(
                            "音频文件保存成功 messageId=" + messageId +
                                ", uri=" + audioUri +
                                ", segmentCount=" + state.audioSegments(messageId).size
                        )
                    }.onFailure { error ->
                        recordFlow(
                            "音频文件保存失败 messageId=" + messageId +
                                ", error=" + (error.message ?: error::class.simpleName)
                        )
                    }
                }
            )
            recordFlow(
                "调用tts.speak messageId=" + messageId +
                    ", textLength=" + ttsText.length +
                    ", mode=" + (if (useWholeReplyTts) "whole_reply" else "sentence") +
                        ", flush=" + flushCalled +
                        ", chunked=false" +
                        ", emotion=none" +
                        ", text=" + ttsText.take(80)
            )

            val startState = withTimeoutOrNull(20_000) {
                tts.playbackState
                    .filter {
                        it.status == PlaybackStatus.Playing ||
                            it.status == PlaybackStatus.Ended ||
                            it.status == PlaybackStatus.Idle ||
                            it.status == PlaybackStatus.Error
                    }
                    .first()
            }

            when (startState?.status) {
                PlaybackStatus.Playing -> {
                    recordFlow("TTS开始播放 messageId=$messageId")
                    if (state.activeMessageId() != messageId) return@LaunchedEffect
                    if (useWholeReplyTts) {
                        // 整段合成：单条音频按估算节奏逐句揭示，近似跟随朗读进度。
                        displayText.voiceCallDisplaySegments().forEach { displaySegment ->
                            if (state.activeMessageId() != messageId) return@LaunchedEffect
                            state.revealThrough(displaySegment.endLength)
                            delay(voiceCallRevealDelayMillis(displaySegment.text))
                        }
                    } else {
                        // 逐句合成：只揭示当前正在朗读的句子，保证气泡文本与音频一一对应。
                        state.revealThrough(segment.endLength)
                    }
                }

                PlaybackStatus.Ended,
                PlaybackStatus.Idle,
                PlaybackStatus.Error,
                null -> {
                    recordFlow("TTS未进入播放态 status=" + (startState?.status ?: "timeout"))
                    state.revealThrough(segment.endLength)
                }

                else -> Unit
            }

            if (startState?.status == PlaybackStatus.Playing) {
                withTimeoutOrNull(180_000) {
                    tts.playbackState
                        .filter {
                            it.status == PlaybackStatus.Ended ||
                                it.status == PlaybackStatus.Error ||
                                it.status == PlaybackStatus.Idle
                        }
                        .first()
                }
                recordFlow(
                    "TTS播放等待结束 messageId=" + messageId +
                        ", status=" + tts.playbackState.value.status
                )
                if (state.activeMessageId() != messageId) return@LaunchedEffect
                state.revealThrough(segment.endLength)
            }

            if (useWholeReplyTts) {
                state.revealThrough(displayText.length)
                state.completeReply()
                break
            }
            nextSpeechIndex++
        }
    }
}

internal data class VoiceCallSpeechSegment(
    val text: String,
    val endLength: Int,
)

internal fun String.voiceCallDisplaySegments(
    includeUnfinishedTail: Boolean = true,
): List<VoiceCallSpeechSegment> {
    val result = mutableListOf<VoiceCallSpeechSegment>()
    var start = 0
    var index = 0
    while (index < length) {
        if (this[index].isVoiceCallSentenceBoundary()) {
            var endExclusive = index + 1
            while (endExclusive < length && this[endExclusive].isVoiceCallSentenceBoundary()) {
                endExclusive++
            }
            val segment = substring(start, endExclusive).trim()
            if (segment.isNotBlank()) {
                result += VoiceCallSpeechSegment(segment, endExclusive)
            }
            start = endExclusive
            while (start < length && this[start].isWhitespace()) start++
            index = start
        } else {
            index++
        }
    }
    if (includeUnfinishedTail) {
        val tail = if (start < length) substring(start).trim() else ""
        if (tail.isNotBlank()) result += VoiceCallSpeechSegment(tail, length)
    }
    return result
}

internal fun estimateVoiceCallDurationSeconds(text: String): Int {
    val chineseChars = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    val otherChars = (text.length - chineseChars).coerceAtLeast(0)
    return ((chineseChars / 4.2f) + (otherChars / 9.0f))
        .toInt()
        .coerceIn(1, 60)
}

private fun Char.isVoiceCallSentenceBoundary(): Boolean =
    this in setOf('。', '！', '？', '.', '!', '?', '…', '\n')

private fun voiceCallRevealDelayMillis(text: String): Long {
    return (estimateVoiceCallDurationSeconds(text) * 300L)
        .coerceIn(450L, 1_200L)
}
