package me.rerere.rikkahub.ui.pages.chat

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import me.rerere.ai.util.GenerationTimingTrace
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.voice.VoiceCallAsrRecovery
import me.rerere.rikkahub.data.voice.VoiceCallUtteranceGate
import me.rerere.rikkahub.data.voice.VOICE_CALL_ASR_STABLE_CONNECTION_MS
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState

internal fun voiceCallTerminalAsrErrorMessage(providerError: String?): String =
    providerError?.takeIf(String::isNotBlank) ?: "语音识别连接失败，请重试或检查 ASR 配置。"

@Stable
internal class VoiceCallDuplexInputState {
    var pendingText by mutableStateOf("")
    var fallback by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var asrState by mutableStateOf(ASRState())
    var muted by mutableStateOf(false)
    var stop: () -> Unit = {}

    fun finishWithError(message: String, onEmptyFinal: () -> Unit) {
        pendingText = ""
        error = message
        onEmptyFinal()
    }

    fun toggleMute() {
        muted = !muted
        if (!muted) {
            error = null
            fallback = false
        }
    }
}

@Composable
internal fun rememberVoiceCallDuplexInput(
    enabled: Boolean,
    permissionGranted: Boolean,
    providerId: kotlin.uuid.Uuid?,
    onSpeechStarted: () -> Unit,
    onEmptyFinal: () -> Unit,
    onInterrupt: () -> Unit,
    onSubmit: (String, GenerationTimingTrace?) -> Unit,
): VoiceCallDuplexInputState {
    val asr = rememberCustomAsrState(fullDuplex = true)
    val normalAsr = LocalASRState.current
    val status by asr.state.collectAsState()
    val state = remember { VoiceCallDuplexInputState() }
    val interrupt by rememberUpdatedState(onInterrupt)
    val submit by rememberUpdatedState(onSubmit)
    val speechStarted by rememberUpdatedState(onSpeechStarted)
    val emptyFinal by rememberUpdatedState(onEmptyFinal)
    var accepting by remember { mutableStateOf(false) }
    val recovery = remember(providerId) { VoiceCallAsrRecovery() }

    LaunchedEffect(status) {
        state.asrState = status
    }
    LaunchedEffect(state.muted) {
        if (!state.muted) recovery.reset()
    }

    DisposableEffect(asr, enabled, permissionGranted, providerId) {
        accepting = enabled && permissionGranted
        state.fallback = false
        state.error = null
        state.pendingText = ""
        emptyFinal()
        if (accepting) normalAsr.stop()
        state.stop = {
            accepting = false
            state.pendingText = ""
            recovery.reset()
            emptyFinal()
            asr.stop()
        }
        onDispose {
            accepting = false
            recovery.reset()
            asr.stop()
        }
    }

    LaunchedEffect(enabled, permissionGranted, providerId, status.status, status.isAvailable, state.muted) {
        if (!enabled || !permissionGranted || state.muted) {
            if (accepting) asr.stop()
            accepting = false
            state.pendingText = ""
            recovery.reset()
            emptyFinal()
            return@LaunchedEffect
        }
        accepting = true
        if (!status.isAvailable) {
            state.fallback = true
            state.error = null
            return@LaunchedEffect
        }
        state.fallback = false
        if (state.error != null) return@LaunchedEffect
        if (status.status == ASRStatus.Connecting) {
            delay(10_000)
            if (accepting && asr.state.value.status == ASRStatus.Connecting) asr.stop()
            return@LaunchedEffect
        }
        if (status.status == ASRStatus.Listening) {
            state.error = null
            if (recovery.listening(SystemClock.elapsedRealtime())) {
                state.pendingText = ""
                emptyFinal()
            }
            delay(VOICE_CALL_ASR_STABLE_CONNECTION_MS)
            if (accepting && asr.state.value.status == ASRStatus.Listening) {
                recovery.resetIfStable(SystemClock.elapsedRealtime())
            }
            return@LaunchedEffect
        }
        if (status.status == ASRStatus.Idle || status.status == ASRStatus.Error ||
            status.status == ASRStatus.Stopping
        ) recovery.disconnected()
        if (status.status != ASRStatus.Idle && status.status != ASRStatus.Error) return@LaunchedEffect
        if (recovery.exhausted) {
            state.finishWithError(voiceCallTerminalAsrErrorMessage(status.errorMessage), emptyFinal)
            accepting = false
            asr.stop()
            return@LaunchedEffect
        }
        delay(recovery.retryDelayMs)
        if (!accepting) return@LaunchedEffect
        val attempt = recovery.startAttempt()
        val gate = VoiceCallUtteranceGate()
        val speechStops = mutableMapOf<String, Long>()
        asr.startUtterances { utterance ->
            if (!accepting || state.fallback || !recovery.isCurrent(attempt)) return@startUtterances
            val receivedAt = System.nanoTime()
            if (utterance.isSpeechStopped) {
                if (BuildConfig.DEBUG) speechStops.putIfAbsent(utterance.id, receivedAt)
                return@startUtterances
            }
            val stoppedAt = if (utterance.isFinal) speechStops.remove(utterance.id) else null
            val action = gate.accept(utterance) ?: return@startUtterances
            recovery.accept(utterance)
            if (utterance.isSpeechStarted || (!utterance.isFinal && utterance.text.isNotBlank())) {
                speechStarted()
            }
            state.pendingText = if (utterance.isFinal) "" else utterance.text
            if (action.interrupt) {
                speechStarted()
                interrupt()
            }
            if (utterance.isFinal && action.submittedText == null) emptyFinal()
            action.submittedText?.let { text ->
                val timing = if (BuildConfig.DEBUG) {
                    GenerationTimingTrace("voice_call_turn", startedAt = stoppedAt ?: receivedAt).apply {
                        stoppedAt?.let { mark("asr_speech_stopped", atNanos = it) }
                        mark("asr_final", characters = text.length, atNanos = receivedAt)
                    }
                } else null
                submit(text, timing)
            }
        }
    }
    return state
}
