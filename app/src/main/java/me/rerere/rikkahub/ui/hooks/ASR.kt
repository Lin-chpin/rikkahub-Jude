package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.asr.ASRUtterance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.ElevenLabsRealtimeASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

@Composable
fun rememberCustomAsrState(fullDuplex: Boolean = false): CustomAsrState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val httpClient = koinInject<OkHttpClient>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    val asrState = remember(fullDuplex) {
        CustomAsrStateImpl(context.applicationContext, httpClient, fullDuplex)
    }

    DisposableEffect(settings.selectedASRProviderId, settings.asrProviders) {
        asrState.updateProvider(settings.getSelectedASRProvider())
        onDispose { }
    }

    DisposableEffect(asrState) {
        onDispose {
            asrState.cleanup()
        }
    }

    return asrState
}

interface CustomAsrState {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun startUtterances(onUtterance: (ASRUtterance) -> Unit)
    fun stop()
    fun cleanup()
}

private class CustomAsrStateImpl(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val fullDuplex: Boolean,
) : CustomAsrState {
    private var controller: ASRController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private val observedState = MutableStateFlow(ASRState())
    private var previousAudioMode: Int? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(
        if (fullDuplex) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { focus ->
            if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }
        .setAcceptsDelayedFocusGain(false)
        .build()

    override val state: StateFlow<ASRState>
        get() = observedState

    fun updateProvider(provider: ASRProviderSetting?) {
        stateJob?.cancel()
        controller?.dispose()
        controller = provider?.let { createController(it) }
        observedState.value = controller?.state?.value ?: ASRState()
        controller?.let { active ->
            stateJob = scope.launch { active.state.collect { observedState.value = it } }
        }
    }

    override fun start(onTranscriptChange: (String) -> Unit) {
        startController(onTranscriptChange, null)
    }

    override fun startUtterances(onUtterance: (ASRUtterance) -> Unit) {
        startController({}, onUtterance)
    }

    private fun startController(
        onTranscriptChange: (String) -> Unit,
        onUtterance: ((ASRUtterance) -> Unit)?,
    ) {
        if (controller == null) return
        if (audioManager.requestAudioFocus(audioFocusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            observedState.value = observedState.value.copy(
                status = me.rerere.asr.ASRStatus.Error, errorMessage = "无法获取麦克风音频焦点",
            )
            return
        }
        if (fullDuplex && previousAudioMode == null) {
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        controller?.start(onTranscriptChange, onUtterance)
    }

    override fun stop() {
        controller?.stop()
        releaseAudioFocus()
    }

    override fun cleanup() {
        controller?.dispose()
        controller = null
        stateJob?.cancel()
        releaseAudioFocus()
        scope.cancel()
    }

    private fun releaseAudioFocus() {
        previousAudioMode?.let { audioManager.mode = it }
        previousAudioMode = null
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun createController(provider: ASRProviderSetting): ASRController? {
        return when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> {
                if (provider.apiKey.isBlank()) return null
                OpenAIRealtimeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.DashScope -> {
                if (provider.apiKey.isBlank()) return null
                DashScopeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Volcengine -> {
                if (provider.apiKey.isBlank()) return null
                VolcengineASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.ElevenLabsRealtime -> {
                if (provider.apiKey.isBlank()) return null
                ElevenLabsRealtimeASRController(context, httpClient, provider)
            }
        }
    }
}
