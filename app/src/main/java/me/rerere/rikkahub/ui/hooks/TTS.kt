package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import me.rerere.tts.model.PlaybackState
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.utils.keepEnglishOnlyForTts
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.CachedAudioSource
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.controller.TtsController
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "TTS"

/**
 * Composable function to remember and manage custom TTS state.
 * Uses user-configured TTS providers instead of system TTS.
 */
@Composable
fun rememberCustomTtsState(): CustomTtsState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    // Remember the CustomTtsState instance across recompositions
    val ttsState = remember {
        CustomTtsStateImpl(
            context = context.applicationContext,
            settingsStore = settingsStore,
            initialProvider = settings.getSelectedTTSProvider(),
        )
    }

    // Update the provider when settings change
    DisposableEffect(settings.selectedTTSProviderId, settings.ttsProviders) {
        ttsState.updateProvider(settings.getSelectedTTSProvider())
        onDispose { }
    }

    // Cleanup resources when the state is disposed
    DisposableEffect(ttsState) {
        onDispose {
            ttsState.cleanup()
        }
    }

    return ttsState
}

/**
 * Interface defining the public API of our custom TTS state holder.
 */
interface CustomTtsState {
    /** Flow indicating if the TTS provider is available and ready. */
    val isAvailable: StateFlow<Boolean>

    /** Becomes true after the runtime has synchronized with persisted provider settings. */
    val isProviderReady: StateFlow<Boolean>

    /** Flow indicating if the TTS is currently speaking. */
    val isSpeaking: StateFlow<Boolean>

    /** Flow holding any error message. */
    val error: StateFlow<String?>

    /** Flow indicating current chunk being processed (index) */
    val currentChunk: StateFlow<Int>

    /** Flow indicating total chunks in queue */
    val totalChunks: StateFlow<Int>

    /** Unified playback state (status, position, duration, speed, etc.) */
    val playbackState: StateFlow<PlaybackState>

    /** Identifies the latest playback request so message-level controls do not follow another message. */
    val playbackSessionId: StateFlow<Long>

    /**
     * Speaks the given text using the selected TTS provider.
     * Long texts will be automatically chunked and queued unless [chunked] is false.
     */
    fun speak(
        text: String,
        flushCalled: Boolean = true,
        chunked: Boolean = true,
        onAudioReady: (suspend (TTSResponse) -> Unit)? = null,
        onAudioReadyWithChunk: (suspend (String, Int, Int, TTSResponse) -> Unit)? = null,
        emotion: String? = null,
    )

    /** Stops the current speech and clears the queue. */
    fun stop()
    fun playCachedAudio(audioUri: String, format: String, sampleRate: Int? = null)
    fun playCachedAudios(audios: List<CachedAudioSource>, flushCalled: Boolean = true): Long

    /** Pauses the current playback. */
    fun pause()

    /** Resumes the paused playback. */
    fun resume()

    /** Skips to the next chunk in the queue. */
    fun skipNext()

    /** Fast forward current playback by [ms]. */
    fun fastForward(ms: Long = 5_000)

    /** Set playback [speed]. */
    fun setSpeed(speed: Float)

    /** Cleanup resources. */
    fun cleanup()
}

/**
 * Internal implementation of CustomTtsState.
 */
private class CustomTtsStateImpl(
    private val context: Context,
    private val settingsStore: SettingsStore,
    initialProvider: TTSProviderSetting?,
) : CustomTtsState, KoinComponent {

    private val ttsManager by inject<TTSManager>()
    private val controller = TtsController(context, ttsManager).also { it.setProvider(initialProvider) }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentJob: Job? = null

    override val isAvailable: StateFlow<Boolean> get() = controller.isAvailable
    override val isProviderReady: StateFlow<Boolean> get() = controller.isProviderReady
    override val isSpeaking: StateFlow<Boolean> get() = controller.isSpeaking
    override val error: StateFlow<String?> get() = controller.error
    override val currentChunk: StateFlow<Int> get() = controller.currentChunk
    override val totalChunks: StateFlow<Int> get() = controller.totalChunks
    override val playbackState: StateFlow<PlaybackState> get() = controller.playbackState
    private val playbackSessionCounter = AtomicLong(0L)
    private val _playbackSessionId = MutableStateFlow(0L)
    override val playbackSessionId: StateFlow<Long> get() = _playbackSessionId

    private fun beginPlaybackSession(): Long {
        val sessionId = playbackSessionCounter.incrementAndGet()
        _playbackSessionId.value = sessionId
        return sessionId
    }

    fun updateProvider(provider: TTSProviderSetting?) {
        controller.setProvider(provider)
    }

    override fun speak(
        text: String,
        flushCalled: Boolean,
        chunked: Boolean,
        onAudioReady: (suspend (TTSResponse) -> Unit)?,
        onAudioReadyWithChunk: (suspend (String, Int, Int, TTSResponse) -> Unit)?,
        emotion: String?,
    ) {
        beginPlaybackSession()
        val settings = settingsStore.settingsFlow.value
        val processed = text.stripMarkdown().let {
            if (settings.displaySetting.ttsEnglishOnly) {
                it.keepEnglishOnlyForTts()
            } else {
                it
            }
        }
        controller.speak(
            text = processed,
            flush = flushCalled,
            chunked = chunked,
            onAudioReady = onAudioReady,
            onAudioReadyWithChunk = onAudioReadyWithChunk?.let { callback ->
                { chunk, chunkIndex, totalChunks, response ->
                    callback(chunk.text, chunkIndex, totalChunks, response)
                }
            },
            emotion = emotion,
        )
    }

    override fun stop() {
        beginPlaybackSession()
        controller.stop()
    }

    override fun playCachedAudio(audioUri: String, format: String, sampleRate: Int?) {
        beginPlaybackSession()
        Log.i(TAG, "Cached audio playback requested: uri=" + audioUri + ", format=" + format + ", sampleRate=" + sampleRate)
        scope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(audioUri)
                    Log.i(TAG, "Reading cached audio: scheme=" + uri.scheme + ", path=" + uri.path)
                    val bytes = when (uri.scheme?.lowercase()) {
                        "file" -> uri.path?.let { java.io.File(it).readBytes() }
                        else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } ?: error("Cached audio input stream is unavailable")
                    val audioFormat = AudioFormat.valueOf(format)
                    Log.i(TAG, "Cached audio read succeeded: bytes=" + bytes.size + ", format=" + audioFormat + ", sampleRate=" + sampleRate)
                    TTSResponse(
                        audioData = bytes,
                        format = audioFormat,
                        sampleRate = sampleRate,
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Cached audio read failed: uri=" + audioUri, error)
                }.getOrNull()
            }
            if (response == null) return@launch

            runCatching {
                Log.i(TAG, "Starting cached audio player")
                // ExoPlayer is main-looper bound; only cached-file I/O runs on IO.
                controller.playCachedAudio(response)
                Log.i(TAG, "Cached audio player started")
            }.onFailure { error ->
                Log.e(TAG, "Cached audio player start failed", error)
            }
        }
    }

    override fun playCachedAudios(audios: List<CachedAudioSource>, flushCalled: Boolean): Long {
        val sessionId = beginPlaybackSession()
        if (audios.isEmpty()) return sessionId
        scope.launch {
            val responses = withContext(Dispatchers.IO) {
                audios.mapNotNull { audio ->
                    readCachedAudio(audio.audioUri, audio.format.name, audio.sampleRate)
                }
            }
            if (responses.isNotEmpty()) {
                controller.playCachedAudioSequence(responses, flush = flushCalled)
            }
        }
        return sessionId
    }

    private fun readCachedAudio(audioUri: String, format: String, sampleRate: Int?): TTSResponse? = runCatching {
        val uri = Uri.parse(audioUri)
        val bytes = when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { java.io.File(it).readBytes() }
            else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: error("Cached audio input stream is unavailable")
        TTSResponse(
            audioData = bytes,
            format = AudioFormat.valueOf(format),
            sampleRate = sampleRate,
        )
    }.onFailure { error ->
        Log.e(TAG, "Cached audio read failed: uri=$audioUri", error)
    }.getOrNull()

    override fun pause() {
        controller.pause()
        Log.d("CustomTtsState", "TTS paused")
    }

    override fun resume() {
        controller.resume()
        Log.d("CustomTtsState", "TTS resumed")
    }

    override fun skipNext() {
        controller.skipNext()
    }

    override fun fastForward(ms: Long) {
        controller.fastForward(ms)
    }

    override fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
    }

    override fun cleanup() {
        controller.dispose()
        currentJob = null
    }
}
