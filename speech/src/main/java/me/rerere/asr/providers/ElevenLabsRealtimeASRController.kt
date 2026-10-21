package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRUtterance
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

private const val TAG = "ElevenLabsRealtimeASR"
private const val SAMPLE_RATE = 16_000
private const val FRAME_SIZE_BYTES = SAMPLE_RATE / 10 * 2
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
private const val REALTIME_URL = "https://api.elevenlabs.io/v1/speech-to-text/realtime"

class ElevenLabsRealtimeASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.ElevenLabsRealtime,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private var onUtterance: ((ASRUtterance) -> Unit)? = null
    private val committedTranscripts = mutableListOf<String>()
    private var partialTranscript = ""
    private var utteranceId = 0L
    @Volatile private var stopRequested = false

    override fun start(
        onTranscriptChange: (String) -> Unit,
        onUtterance: ((ASRUtterance) -> Unit)?,
    ) {
        if (_state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setError("Microphone permission is required")
            return
        }

        webSocket?.cancel()
        stopRequested = false
        this.onTranscriptChange = onTranscriptChange
        this.onUtterance = onUtterance
        committedTranscripts.clear()
        partialTranscript = ""
        utteranceId = 0
        _state.value = ASRState(status = ASRStatus.Connecting, isAvailable = true)

        val request = Request.Builder()
            .url(provider.websocketEndpoint())
            .addHeader("xi-api-key", provider.apiKey)
            .build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (this@ElevenLabsRealtimeASRController.webSocket === webSocket) {
                        handleServerEvent(text, webSocket)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    if (this@ElevenLabsRealtimeASRController.webSocket !== webSocket) return@launch
                    Log.e(TAG, "Realtime ASR websocket failed", t)
                    releaseRecorder()
                    setError(t.message ?: "ASR websocket failed")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (this@ElevenLabsRealtimeASRController.webSocket !== webSocket) return@launch
                    releaseRecorder()
                    _state.update {
                        if (it.status == ASRStatus.Error) it
                        else it.copy(status = ASRStatus.Idle, errorMessage = null)
                    }
                }
            }
        })
    }

    override fun stop() {
        if (stopRequested) return
        stopRequested = true
        recorderJob?.cancel()
        releaseRecorder()
        val socket = webSocket
        if (socket == null) {
            _state.update { it.copy(status = ASRStatus.Idle) }
            return
        }

        _state.update { it.copy(status = ASRStatus.Stopping) }
        socket.send(
            JSONObject()
                .put("message_type", "input_audio_chunk")
                .put("audio_base_64", "")
                .put("sample_rate", SAMPLE_RATE)
                .put("commit", true)
                .toString()
        )
        scope.launch {
            delay(2_000)
            if (this@ElevenLabsRealtimeASRController.webSocket === socket) {
                socket.close(1000, "stop")
                webSocket = null
                _state.update {
                    if (it.status == ASRStatus.Stopping) it.copy(status = ASRStatus.Idle) else it
                }
            }
        }
    }

    override fun dispose() {
        onTranscriptChange = null
        onUtterance = null
        val socket = webSocket
        webSocket = null
        recorderJob?.cancel()
        releaseRecorder()
        socket?.cancel()
        scope.cancel()
    }

    private fun handleServerEvent(text: String, socket: WebSocket) {
        val event = runCatching { JSONObject(text) }.getOrElse {
            Log.w(TAG, "Invalid realtime event", it)
            return
        }

        when (val type = event.optString("message_type")) {
            "session_started" -> {
                _state.update { it.copy(status = if (stopRequested) ASRStatus.Stopping else ASRStatus.Listening) }
                if (!stopRequested) startRecorder(socket)
            }

            "partial_transcript" -> {
                partialTranscript = event.optString("text").trim()
                publishTranscript()
                if (partialTranscript.isNotEmpty()) {
                    onUtterance?.invoke(ASRUtterance(utteranceId.toString(), partialTranscript, false))
                }
            }

            "committed_transcript" -> {
                val transcript = event.optString("text").trim()
                if (transcript.isNotEmpty()) {
                    committedTranscripts += transcript
                    onUtterance?.invoke(ASRUtterance(utteranceId.toString(), transcript, true))
                }
                utteranceId++
                partialTranscript = ""
                publishTranscript()
            }

            "warning" -> Unit
            else -> {
                if (type.isNotBlank()) {
                    val message = event.optJSONObject("error")?.optString("message").orEmpty()
                        .ifBlank { event.optString("error") }
                        .ifBlank { event.optString("message") }
                        .ifBlank { type }
                    setError(message)
                }
            }
        }
    }

    private fun publishTranscript() {
        val transcript = (committedTranscripts + partialTranscript)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        onTranscriptChange?.invoke(transcript)
    }

    private fun setError(message: String) {
        stopRequested = true
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(socket: WebSocket) {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            var echoCanceler: AcousticEchoCanceler? = null
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(FRAME_SIZE_BYTES)
                val activeRecorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2,
                )
                recorder = activeRecorder
                audioRecord = activeRecorder
                echoCanceler = me.rerere.asr.enableAcousticEchoCancellation(activeRecorder.audioSessionId)
                activeRecorder.startRecording()
                val buffer = ByteArray(FRAME_SIZE_BYTES)
                while (isActive && !stopRequested) {
                    val read = activeRecorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(calculateRmsAmplitude(buffer, read))) }
                        if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                            socket.send(
                                JSONObject()
                                    .put("message_type", "input_audio_chunk")
                                    .put("audio_base_64", Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
                                    .put("sample_rate", SAMPLE_RATE)
                                    .toString()
                            )
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                if (isActive && !stopRequested) {
                    Log.e(TAG, "Audio recording failed", e)
                    setError(e.message ?: "Audio recording failed")
                }
            } finally {
                echoCanceler?.release()
                recorder?.let { activeRecorder ->
                    synchronized(this@ElevenLabsRealtimeASRController) {
                        if (audioRecord === activeRecorder) audioRecord = null
                    }
                    runCatching { activeRecorder.stop() }
                    runCatching { activeRecorder.release() }
                }
            }
        }
    }

    @Synchronized
    private fun releaseRecorder() {
        recorderJob?.cancel()
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}

internal fun ASRProviderSetting.ElevenLabsRealtime.websocketEndpoint(): String {
    return REALTIME_URL.toHttpUrl().newBuilder()
        .addQueryParameter("model_id", "scribe_v2_realtime")
        .addQueryParameter("audio_format", "pcm_16000")
        .addQueryParameter("commit_strategy", "vad")
        .addQueryParameter("vad_silence_threshold_secs", "1.5")
        .addQueryParameter("keepalive_interval_ms", "1000")
        .apply { if (language.isNotBlank()) addQueryParameter("language_code", language.trim()) }
        .build()
        .toString()
        .replaceFirst("https://", "wss://")
}
