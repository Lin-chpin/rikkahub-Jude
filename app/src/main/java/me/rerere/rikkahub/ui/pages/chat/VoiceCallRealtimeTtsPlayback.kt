package me.rerere.rikkahub.ui.pages.chat

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.VoiceCallAudioSegment
import me.rerere.ai.util.GenerationTimingTrace
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.voice.VoiceCallSpeechInput
import me.rerere.rikkahub.data.voice.withoutIncompleteVoiceCallAudioTag
import me.rerere.rikkahub.data.voice.withoutVoiceCallRealtimeEmotionMarker
import me.rerere.rikkahub.service.sanitizeRealtimeVoiceCallTextForSpeech
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse

@Composable
internal fun BindVoiceCallRealtimeTtsPlayback(
    state: VoiceCallSpeechPlaybackState,
    awaitInitialAssistantReply: Boolean,
    speechInput: StateFlow<VoiceCallSpeechInput>,
    loadingJob: Job?,
    ttsOnlyReadQuoted: Boolean,
    fullDuplex: Boolean,
    tts: CustomTtsState,
    filesManager: FilesManager,
    recordFlow: (String) -> Unit,
) {
    val latestLoadingJob by rememberUpdatedState(loadingJob)

    LaunchedEffect(awaitInitialAssistantReply) {
        if (awaitInitialAssistantReply && !state.replyPending) state.beginReply()
    }

    LaunchedEffect(state.replyPending, state.replyGeneration) {
        if (!state.replyPending) return@LaunchedEffect
        // Start alongside generation, before the first assistant message or text exists.
        // The reply generation keeps interruption/restart distinct even within one UI frame.
        val sourceDeadline = SystemClock.elapsedRealtime() + 30_000
        var generationJob: Job? = null
        var lastGenerationGate: String? = null
        while (state.replyPending) {
            val timing = GenerationTimingTrace("voice_call_tts")
            var messageId: String? = null
            val speechChannel = Channel<String>(Channel.UNLIMITED)
            var sentSpeechText = ""
            var finalSpeechText = ""
            var completed = false
            val audioSaved = CompletableDeferred<Unit>()
            state.trackAudioSave(audioSaved)
            val alignment = VoiceCallAudioAlignment()
            val playbackSessionId = tts.speakRealtimeDialogue(
                text = speechChannel.receiveAsFlow(),
                onAlignment = alignment::append,
                onAudioReady = { response ->
                    recordFlow(
                        "实时 TTS音频回调 session=${tts.playbackSessionId.value} " +
                            "streamSession=${response.metadata["timing_id"]} " +
                            "bytes=${response.audioData.size} format=${response.format} " +
                            "messageId=${messageId ?: "none"} textChars=${finalSpeechText.length}"
                    )
                    try {
                        messageId?.let { id ->
                            saveRealtimeCallAudio(
                                state = state,
                                filesManager = filesManager,
                                messageId = id,
                                text = finalSpeechText,
                                response = response,
                                recordFlow = recordFlow,
                            )
                        }
                    } finally {
                        audioSaved.complete(Unit)
                    }
                },
            )
            Log.i("VoiceCallRealtimeTts", "Preconnecting: playbackSession=$playbackSessionId")
            recordFlow("实时 TTS 预连接已启动 session=$playbackSessionId")
            // Text input can finish before playback starts; keep progress alive while the sender awaits Ended.
            val progressJob = launch {
                var lastProgressMode: String? = null
                while (state.replyPending && tts.playbackSessionId.value == playbackSessionId) {
                    val input = speechInput.value
                    val playback = tts.playbackState.value
                    if (messageId != null && input.message?.id?.toString() == messageId &&
                        playback.status == PlaybackStatus.Playing
                    ) {
                        val displayText = input.displayText()
                        val alignedLength = alignment.visibleTextLength(displayText, playback.positionMs, input.finished)
                        val mode = if (alignedLength != null) "alignment" else "estimated"
                        if (mode != lastProgressMode) {
                            recordFlow("实时气泡进度 session=$playbackSessionId messageId=$messageId mode=$mode")
                            lastProgressMode = mode
                        }
                        val visibleLength = alignedLength ?: estimatedVoiceCallVisibleTextLength(displayText, playback.positionMs, input.finished)
                        if (visibleLength > state.visibleTextLength) {
                            state.revealThrough(visibleLength)
                            recordFlow("实时气泡已更新 session=$playbackSessionId positionMs=${playback.positionMs} visibleChars=$visibleLength")
                        }
                    }
                    delay(50)
                }
            }
            try {
                while (state.replyPending) {
                    val input = speechInput.value
                    if (generationJob == null) {
                        val gate = when {
                            input.generationJob == null -> "source_missing"
                            input.generationJob.isCancelled || input.cancelled -> "source_cancelled"
                            input.generationJob === state.excludedSpeechJob -> "previous_source"
                            latestLoadingJob != null && input.generationJob !== latestLoadingJob -> "job_mismatch"
                            else -> null
                        }
                        if (gate != null) {
                            if (SystemClock.elapsedRealtime() >= sourceDeadline) {
                                recordFlow("实时 TTS等待生成源超时")
                                state.completeReply()
                                return@LaunchedEffect
                            }
                            if (lastGenerationGate != gate) {
                                recordFlow(
                                    "实时 TTS等待生成源 gate=$gate " +
                                        "loadingJobActive=${latestLoadingJob?.isActive == true} " +
                                        "inputFinished=${input.finished} cancelled=${input.cancelled}"
                                )
                                lastGenerationGate = gate
                            }
                            delay(20)
                            continue
                        }
                        if (input.generationJob == null) continue
                        generationJob = input.generationJob
                        lastGenerationGate = null
                        recordFlow("实时 TTS已绑定生成源 inputFinished=${input.finished}")
                    }
                    if (input.generationJob !== generationJob || input.cancelled || generationJob?.isCancelled == true) {
                        recordFlow(
                            "实时 TTS生成源中断 sameJob=${input.generationJob === generationJob} " +
                                "cancelled=${input.cancelled}"
                        )
                        return@LaunchedEffect
                    }
                    val loading = !input.finished
                    val candidateId = input.message?.id?.toString()
                    val rawText = input.message?.toText().orEmpty()
                    val displayText = input.displayText()
                    if (candidateId == null) {
                        if (!loading) {
                            recordFlow(
                                "实时 TTS输入结束但无助手消息 cancelled=${input.cancelled} " +
                                    "displayChars=${displayText.length} rawChars=${rawText.length}"
                            )
                            state.completeReply()
                            break
                        }
                        delay(20)
                        continue
                    }
                    if (messageId == null) {
                        messageId = candidateId
                        state.synchronizeMessage(candidateId)
                        recordFlow(
                            "实时 TTS收到助手消息 messageId=$candidateId " +
                                "rawChars=${rawText.length} displayChars=${displayText.length}"
                        )
                    } else if (candidateId != messageId) {
                        // A tool continuation can create another assistant message.
                        break
                    }
                    if (rawText.isNotBlank()) {
                        timing.firstText("raw_text_observed", candidateId, rawText.length)
                    }
                    val stableRawText = rawText
                        .withoutIncompleteVoiceCallAudioTag()
                        .withoutVoiceCallRealtimeEmotionMarker()
                    val speechText = stableRawText
                        .sanitizeRealtimeVoiceCallTextForSpeech(
                            ttsOnlyReadQuoted = ttsOnlyReadQuoted,
                            fullDuplex = fullDuplex,
                            allowUnquotedFallback = input.finished,
                        )

                    finalSpeechText = speechText
                    if (speechText.startsWith(sentSpeechText)) {
                        val delta = speechText.substring(sentSpeechText.length)
                        if (delta.isNotEmpty()) {
                            if (sentSpeechText.isEmpty()) {
                                Log.i(
                                    "VoiceCallRealtimeTts",
                                    "First text available: playbackSession=$playbackSessionId " +
                                        "messageId=$messageId chars=${delta.length} loading=$loading",
                                )
                            }
                            speechChannel.send(delta)
                            sentSpeechText = speechText
                        }
                    }

                    if (!loading) {
                        timing.mark("speech_input_finished_observed", candidateId, speechText.length)
                        finalSpeechText = speechText
                        recordFlow(
                            "实时 TTS输入结束 messageId=$candidateId rawChars=${rawText.length} " +
                                "speechChars=${speechText.length} sentChars=${sentSpeechText.length}"
                        )
                        speechChannel.close()
                        if (sentSpeechText.isEmpty()) {
                            recordFlow("实时 TTS无可朗读文本 messageId=$candidateId")
                            if (tts.playbackSessionId.value == playbackSessionId) tts.stop()
                            state.revealThrough(displayText.length)
                            state.completeReply()
                            completed = true
                            break
                        }
                        val sessionId = playbackSessionId
                        val endState = withTimeoutOrNull(180_000) {
                            tts.playbackState
                                .filter {
                                    tts.playbackSessionId.value == sessionId &&
                                        it.status in setOf(
                                            PlaybackStatus.Ended,
                                            PlaybackStatus.Error,
                                            PlaybackStatus.Idle,
                                        )
                                }
                                .first()
                        }
                        if (endState?.status == PlaybackStatus.Ended) {
                            withTimeoutOrNull(30_000) { audioSaved.await() }
                        }
                        if (endState == null && tts.playbackSessionId.value == sessionId) {
                            tts.stop()
                        }
                        recordFlow(
                            "实时 TTS 结束 messageId=$messageId status=" +
                                (endState?.status ?: "timeout") +
                                " sessionMatches=${tts.playbackSessionId.value == sessionId} " +
                                "audioCallback=${audioSaved.isCompleted}",
                        )
                        if (state.activeMessageId() != messageId) return@LaunchedEffect
                        state.revealThrough(displayText.length)
                        state.completeReply()
                        completed = true
                        break
                    }

                    delay(20)
                }
            } finally {
                progressJob.cancel()
                if (completed) speechChannel.close() else speechChannel.cancel()
                if (!completed && tts.playbackSessionId.value == playbackSessionId) {
                    tts.stop()
                }
            }
        }
    }
}

private suspend fun saveRealtimeCallAudio(
    state: VoiceCallSpeechPlaybackState,
    filesManager: FilesManager,
    messageId: String,
    text: String,
    response: TTSResponse,
    recordFlow: (String) -> Unit,
) {
    val interruptedAtMs = response.metadata["interrupted_at_ms"]?.toLongOrNull()
    if (response.audioData.isEmpty() || interruptedAtMs == 0L) {
        recordFlow("实时 TTS音频为空 messageId=$messageId")
        return
    }
    recordFlow(
        "收到实时 TTS 音频 messageId=$messageId bytes=${response.audioData.size} " +
            "format=${response.format} sampleRate=${response.sampleRate}",
    )
    runCatching {
        val file = filesManager.saveManagedFromBytes(
            folder = FileFolders.UPLOAD,
            bytes = response.audioData,
            displayName = "voice-call-audio",
            mimeType = "audio/*",
        )
        val audioUri = filesManager.getFile(file).toUri().toString()
        var alignmentFailure = "unknown"
        val ranges = alignedVoiceCallAudioRanges(text, response) { alignmentFailure = it }
        if (ranges == null) {
            recordFlow(
                "实时音频按整段回放 messageId=$messageId reason=$alignmentFailure " +
                    "textChars=${text.length} alignmentChunks=${response.metadata["alignment_chunk_count"]}",
            )
        }
        val audibleRanges = ranges?.filter { interruptedAtMs == null || it.startPositionMs < interruptedAtMs }
        val segments = audibleRanges?.map { range ->
            VoiceCallAudioSegment(
                text = range.text,
                audioUri = audioUri,
                format = response.format.name,
                sampleRate = response.sampleRate,
                startPositionMs = range.startPositionMs,
                endPositionMs = interruptedAtMs?.let { minOf(it, range.endPositionMs) } ?: range.endPositionMs,
            )
        } ?: listOf(
            VoiceCallAudioSegment(
                text = text,
                audioUri = audioUri,
                format = response.format.name,
                sampleRate = response.sampleRate,
                endPositionMs = interruptedAtMs,
            ),
        )
        segments.forEach { segment -> state.appendAudioSegment(messageId, segment) }
        recordFlow(
            "实时 TTS 音频已保存 messageId=$messageId bytes=${response.audioData.size} " +
                "sentenceRanges=${ranges?.size ?: 0} alignmentComplete=${response.metadata["alignment_complete"]}",
        )
    }.onFailure { error ->
        recordFlow("实时 TTS 音频保存失败 messageId=$messageId error=${error::class.simpleName}")
    }
}
