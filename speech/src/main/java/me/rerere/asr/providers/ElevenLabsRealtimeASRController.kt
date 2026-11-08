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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRUtterance
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import me.rerere.asr.isExpectedAsrWebSocketClosure
import me.rerere.asr.realtimeAsrWebSocketClosedMessage
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit

private const val TAG = "ElevenLabsRealtimeASR"
private const val SAMPLE_RATE = 16_000
private const val FRAME_SIZE_BYTES = SAMPLE_RATE / 10 * 2
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
private const val REALTIME_URL = "https://api.elevenlabs.io/v1/speech-to-text/realtime"

private val recognizedElevenLabsRealtimeErrorTypes = setOf(
    "auth_error", "unaccepted_terms", "quota_exceeded", "payment_issue", "payment_required",
    "insufficient_quota", "rate_limited", "commit_throttled", "queue_overflow", "resource_exhausted",
    "session_time_limit_exceeded", "input_error", "invalid_request", "chunk_size_exceeded",
    "insufficient_audio_activity", "transcriber_error", "error",
)

internal data class ElevenLabsRealtimeServerEvent(
    val messageType: String,
    val transcript: String?,
    val errorType: String?,
)

internal fun parseElevenLabsRealtimeEvent(text: String): ElevenLabsRealtimeServerEvent? = runCatching {
    val root = Json.parseToJsonElement(text).jsonObject
    fun stringValue(value: JsonObject?, key: String): String? =
        (value?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun normalizeErrorType(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf(recognizedElevenLabsRealtimeErrorTypes::contains)
    val error = runCatching { root["error"]?.jsonObject }.getOrNull()
    val errorType = sequenceOf(
        stringValue(error, "type"),
        stringValue(error, "code"),
        stringValue(error, "status"),
        stringValue(root, "error_type"),
    ).mapNotNull(::normalizeErrorType).firstOrNull()
    val messageType = stringValue(root, "message_type")?.takeIf(String::isNotBlank)
        ?: error("Missing or invalid message_type")
    ElevenLabsRealtimeServerEvent(
        messageType = messageType,
        transcript = stringValue(root, "text"),
        errorType = errorType,
    )
}.getOrNull()

internal fun elevenLabsRealtimeASRErrorMessage(
    serverErrorType: String? = null,
    httpStatus: Int? = null,
    transportError: Throwable? = null,
): String {
    when (httpStatus) {
        401 -> return "ElevenLabs Scribe 鉴权失败，请检查 API Key 或账号登录状态。"
        403 -> return "ElevenLabs Scribe 拒绝访问，请检查账号权限和服务权限。"
        429 -> return "ElevenLabs Scribe 请求受到限流或额度限制，请稍后重试并检查账户用量。"
        in 500..599 -> return "ElevenLabs Scribe 返回服务端错误（HTTP $httpStatus），请稍后重试。"
        404 -> return "ElevenLabs Scribe 未找到请求的资源，请检查服务地址和模型配置。"
        408 -> return "ElevenLabs Scribe 请求超时，请稍后重试。"
        in 400..499 -> return "ElevenLabs Scribe 拒绝了请求（HTTP $httpStatus），请检查请求配置。"
    }

    when (serverErrorType) {
        "auth_error" -> return "ElevenLabs Scribe 鉴权失败，请检查 API Key 或账号登录状态。"
        "unaccepted_terms" -> return "请先在 ElevenLabs 账户接受 Scribe 服务条款。"
        "quota_exceeded" -> return "ElevenLabs Scribe 额度不足，请检查账户用量。"
        "payment_issue", "payment_required", "insufficient_quota" ->
            return "ElevenLabs Scribe 要求检查账户付款状态或可用额度。"
        "rate_limited", "commit_throttled" -> return "ElevenLabs Scribe 请求受到限流，请稍后重试。"
        "queue_overflow", "resource_exhausted" -> return "ElevenLabs Scribe 服务报告资源耗尽，请稍后重试。"
        "session_time_limit_exceeded" -> return "本次语音识别已达到时长限制，请重新开始通话。"
        "input_error", "invalid_request", "chunk_size_exceeded" ->
            return "ElevenLabs Scribe 拒绝了音频请求，请检查语音识别配置。"
        "insufficient_audio_activity" -> return "没有检测到足够的语音，请靠近麦克风后重试。"
        "error", "transcriber_error" -> return "ElevenLabs Scribe 报告语音处理失败，具体原因无法确认。"
    }

    if (serverErrorType != null) {
        return "ElevenLabs Scribe 报告了无法识别的错误，具体原因无法确认。"
    }

    return when (transportError) {
        is UnknownHostException -> "无法解析 ElevenLabs Scribe 域名，请检查服务地址和网络连接。"
        is SSLException -> "无法建立 ElevenLabs Scribe 安全连接。"
        is SocketTimeoutException -> "等待 ElevenLabs Scribe 响应超时。"
        is IOException -> "ElevenLabs Scribe 连接中断。"
        null -> "ElevenLabs Scribe 连接失败，具体原因无法确认。"
        else -> "ElevenLabs Scribe 连接失败，具体原因无法确认。"
    }
}

fun normalizeElevenLabsScribeLanguageCode(language: String): String = when (language.trim().lowercase()) {
    "zn", "zho" -> "zh"
    else -> language.trim()
}

class ElevenLabsRealtimeASRController(
    private val context: Context,
    httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.ElevenLabsRealtime,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val realtimeClient = httpClient.newBuilder().pingInterval(5, TimeUnit.SECONDS).build()
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
        webSocket = realtimeClient.newWebSocket(request, object : WebSocketListener() {
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
                    val errorType = generateSequence(t as Throwable?) { it.cause }
                        .take(4)
                        .joinToString(">") { it::class.simpleName ?: "Throwable" }
                    Log.e(TAG, "Realtime ASR websocket failed responseCode=${response?.code ?: "none"} causeTypes=$errorType")
                    setError(elevenLabsRealtimeASRErrorMessage(httpStatus = response?.code, transportError = t))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (this@ElevenLabsRealtimeASRController.webSocket !== webSocket) return@launch
                    webSocket.close(1000, "peer_closed")
                    handleWebSocketClosed(webSocket, code)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    handleWebSocketClosed(webSocket, code)
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
        val event = parseElevenLabsRealtimeEvent(text) ?: run {
            Log.w(TAG, "Invalid realtime event JSON")
            setError("ElevenLabs Scribe 返回无法解析的响应，请稍后重试。")
            return
        }

        when (event.messageType) {
            "session_started" -> {
                _state.update { it.copy(status = if (stopRequested) ASRStatus.Stopping else ASRStatus.Listening) }
                if (!stopRequested) startRecorder(socket)
            }

            "partial_transcript" -> {
                partialTranscript = event.transcript.orEmpty().trim()
                publishTranscript()
                if (partialTranscript.isNotEmpty()) {
                    onUtterance?.invoke(ASRUtterance(utteranceId.toString(), partialTranscript, false))
                }
            }

            "committed_transcript" -> {
                val transcript = event.transcript.orEmpty().trim()
                if (transcript.isNotEmpty()) {
                    committedTranscripts += transcript
                }
                onUtterance?.invoke(ASRUtterance(utteranceId.toString(), transcript, true))
                utteranceId++
                partialTranscript = ""
                publishTranscript()
            }

            "warning" -> Unit
            "error" -> {
                val errorType = event.errorType ?: "error"
                Log.w(TAG, "Realtime ASR server error type=$errorType")
                setError(elevenLabsRealtimeASRErrorMessage(serverErrorType = errorType))
            }
            else -> {
                if (event.messageType.isNotBlank()) {
                    Log.w(TAG, "Ignoring unsupported realtime ASR event")
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
        val socket = webSocket
        webSocket = null
        releaseRecorder()
        socket?.cancel()
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun handleWebSocketClosed(webSocket: WebSocket, code: Int) {
        if (this.webSocket !== webSocket) return
        val userStopped = stopRequested
        stopRequested = true
        this.webSocket = null
        releaseRecorder()
        when {
            _state.value.status == ASRStatus.Error -> Unit
            isExpectedAsrWebSocketClosure(userStopped, code) ->
                _state.update { it.copy(status = ASRStatus.Idle, errorMessage = null) }
            else -> {
                Log.w(TAG, "Realtime ASR websocket closed code=$code")
                setError(realtimeAsrWebSocketClosedMessage("ElevenLabs Scribe", code))
            }
        }
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
                        check(socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) { "ASR audio send queue is full" }
                        check(socket.send(
                            JSONObject()
                                .put("message_type", "input_audio_chunk")
                                .put("audio_base_64", Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
                                .put("sample_rate", SAMPLE_RATE)
                                .toString()
                        )) { "ASR websocket refused audio" }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                if (isActive && !stopRequested) {
                    Log.e(TAG, "Audio recording failed cause=${e::class.simpleName}")
                    scope.launch {
                        if (this@ElevenLabsRealtimeASRController.webSocket === socket && !stopRequested) {
                            setError("麦克风录音失败，请检查系统麦克风后重试。")
                        }
                    }
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
        .apply {
            normalizeElevenLabsScribeLanguageCode(language)
                .takeIf(String::isNotBlank)
                ?.let { addQueryParameter("language_code", it) }
        }
        .build()
        .toString()
        .replaceFirst("https://", "wss://")
}
