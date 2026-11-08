package me.rerere.rikkahub.data.voice

import me.rerere.asr.ASRUtterance

internal const val VOICE_CALL_ASR_STABLE_CONNECTION_MS = 30_000L

/** A recovered socket must not submit the previous socket's unfinished utterance. */
internal class VoiceCallAsrRecovery {
    var attempts = 0
        private set
    private var generation = 0L
    private var listeningSinceMs: Long? = null
    private var speechInProgress = false
    private var clearSpeechOnRecovery = false

    val exhausted: Boolean get() = attempts >= 3
    val retryDelayMs: Long get() = if (attempts == 0) 100 else 500L * attempts

    fun startAttempt(): Long {
        attempts++
        return ++generation
    }

    fun isCurrent(attempt: Long): Boolean = attempt == generation

    fun accept(utterance: ASRUtterance) {
        if (utterance.isFinal || utterance.isSpeechStarted || utterance.text.isNotBlank()) {
            clearSpeechOnRecovery = false
            speechInProgress = !utterance.isFinal
        }
    }

    fun disconnected() {
        generation++
        listeningSinceMs = null
        clearSpeechOnRecovery = clearSpeechOnRecovery || speechInProgress
        speechInProgress = false
    }

    fun listening(nowMs: Long): Boolean {
        if (listeningSinceMs == null) listeningSinceMs = nowMs
        val clearSpeech = clearSpeechOnRecovery
        clearSpeechOnRecovery = false
        return clearSpeech
    }

    fun resetIfStable(nowMs: Long) {
        val startedAt = listeningSinceMs ?: return
        if (nowMs - startedAt >= VOICE_CALL_ASR_STABLE_CONNECTION_MS) attempts = 0
    }

    fun reset() {
        generation++
        attempts = 0
        listeningSinceMs = null
        speechInProgress = false
        clearSpeechOnRecovery = false
    }
}
