package me.rerere.rikkahub.ui.pages.chat

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
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
import me.rerere.rikkahub.service.sanitizeVoiceCallTextForSpeech
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse

@Composable
internal fun BindVoiceCallRealtimeTtsPlayback(
    state: VoiceCallSpeechPlaybackState,
    awaitInitialAssistantReply: Boolean,
    speechInput: StateFlow<VoiceCallSpeechInput>,
    loadingJob: Job?,
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
        snapshotFlow { latestLoadingJob != null }.first { it }
        var generationJob: Job? = null
        while (state.replyPending) {
            val timing = GenerationTimingTrace("voice_call_tts")
            var messageId: String? = null
            val speechChannel = Channel<String>(Channel.UNLIMITED)
            var sentSpeechText = ""
            var finalSpeechText = ""
            var completed = false
            val audioSaved = CompletableDeferred<Unit>()
            val playbackSessionId = tts.speakRealtimeDialogue(
                text = speechChannel.receiveAsFlow(),
                onAudioReady = { response ->
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
            recordFlow("Turbo TTS 预连接已启动 session=$playbackSessionId")
            try {
                while (state.replyPending) {
                    val input = speechInput.value
                    if (generationJob == null) {
                        if (input.generationJob == null || !input.generationJob.isActive || input.generationJob !== latestLoadingJob) {
                            delay(20)
                            continue
                        }
                        generationJob = input.generationJob
                    }
                    if (input.generationJob !== generationJob || input.cancelled) return@LaunchedEffect
                    val loading = !input.finished
                    val candidateId = input.message?.id?.toString()
                    val rawText = input.message?.toText().orEmpty()
                    val displayText = input.displayText()
                    if (candidateId == null) {
                        if (!loading) {
                            state.completeReply()
                            break
                        }
                        delay(20)
                        continue
                    }
                    if (messageId == null) {
                        messageId = candidateId
                        state.synchronizeMessage(candidateId)
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
                        .sanitizeVoiceCallTextForSpeech()

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
                        speechChannel.close()
                        if (sentSpeechText.isEmpty()) {
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
                            "Turbo 实时 TTS 结束 messageId=$messageId status=" +
                                (endState?.status ?: "timeout"),
                        )
                        if (state.activeMessageId() != messageId) return@LaunchedEffect
                        state.revealThrough(displayText.length)
                        state.completeReply()
                        completed = true
                        break
                    }

                    if (tts.playbackState.value.status == PlaybackStatus.Playing) {
                        state.revealThrough(
                            minOf(displayText.length, stableRawText.length),
                        )
                    }
                    delay(20)
                }
            } finally {
                speechChannel.close()
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
    if (response.audioData.isEmpty()) return
    recordFlow(
        "收到 Turbo TTS 音频 messageId=$messageId bytes=${response.audioData.size} " +
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
        state.appendAudioSegment(
            messageId = messageId,
            segment = VoiceCallAudioSegment(
                text = text,
                audioUri = audioUri,
                format = response.format.name,
                sampleRate = response.sampleRate,
            ),
        )
        recordFlow("Turbo TTS 音频已保存 messageId=$messageId uri=$audioUri")
    }.onFailure { error ->
        recordFlow("Turbo TTS 音频保存失败 messageId=$messageId error=${error.message ?: error::class.simpleName}")
    }
}
