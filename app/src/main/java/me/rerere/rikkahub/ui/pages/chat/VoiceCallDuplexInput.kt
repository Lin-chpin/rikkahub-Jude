package me.rerere.rikkahub.ui.pages.chat

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
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.data.voice.VoiceCallUtteranceGate
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState

@Stable
internal class VoiceCallDuplexInputState {
    var pendingText by mutableStateOf("")
    var fallback by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var asrState by mutableStateOf(ASRState())
    var muted by mutableStateOf(false)
    var stop: () -> Unit = {}

    fun toggleMute() {
        muted = !muted
        if (!muted) {
            error = null
            fallback = false
        }
    }
}

internal fun shouldClearVoiceCallPendingText(
    enabled: Boolean,
    permissionGranted: Boolean,
    muted: Boolean,
): Boolean = !enabled || permissionGranted || muted

@Composable
internal fun rememberVoiceCallDuplexInput(
    enabled: Boolean,
    permissionGranted: Boolean,
    providerId: kotlin.uuid.Uuid?,
    onSpeechStarted: () -> Unit,
    onEmptyFinal: () -> Unit,
    onInterrupt: () -> Unit,
    onSubmit: (String) -> Unit,
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
    var attempts by remember(providerId) { mutableStateOf(0) }

    LaunchedEffect(status) {
        state.asrState = status
    }
    LaunchedEffect(state.muted) {
        if (!state.muted) attempts = 0
    }

    DisposableEffect(asr, enabled, permissionGranted, providerId) {
        accepting = enabled && permissionGranted
        state.fallback = false
        state.error = null
        if (shouldClearVoiceCallPendingText(enabled, permissionGranted, state.muted)) {
            state.pendingText = ""
        }
        if (accepting) normalAsr.stop()
        state.stop = {
            accepting = false
            state.pendingText = ""
            asr.stop()
        }
        onDispose {
            accepting = false
            asr.stop()
        }
    }

    LaunchedEffect(enabled, permissionGranted, providerId, status.status, status.isAvailable, state.muted) {
        if (!enabled || !permissionGranted || state.muted) {
            if (accepting) asr.stop()
            accepting = false
            if (shouldClearVoiceCallPendingText(enabled, permissionGranted, state.muted)) {
                state.pendingText = ""
            }
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
            return@LaunchedEffect
        }
        if (status.status != ASRStatus.Idle && status.status != ASRStatus.Error) return@LaunchedEffect
        if (attempts >= 3) {
            state.error = "语音识别连接失败，请重试或检查 ASR 配置。"
            accepting = false
            asr.stop()
            return@LaunchedEffect
        }
        delay(if (attempts == 0) 100 else 500L * attempts)
        if (!accepting) return@LaunchedEffect
        attempts++
        val gate = VoiceCallUtteranceGate()
        asr.startUtterances { utterance ->
            if (!accepting || state.fallback) return@startUtterances
            attempts = 0
            if (utterance.isSpeechStarted || (!utterance.isFinal && utterance.text.isNotBlank())) {
                speechStarted()
            }
            val action = gate.accept(utterance) ?: return@startUtterances
            state.pendingText = if (utterance.isFinal) "" else utterance.text
            if (action.interrupt) {
                speechStarted()
                interrupt()
            }
            if (utterance.isFinal && action.submittedText == null) emptyFinal()
            action.submittedText?.let(submit)
        }
    }
    return state
}
