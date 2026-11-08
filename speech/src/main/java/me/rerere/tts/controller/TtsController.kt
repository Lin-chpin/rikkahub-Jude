package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderErrorCode
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSResponseParsingException
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.forRealtimeCall
import me.rerere.tts.provider.isElevenLabsV4Family
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val TAG = "TtsController"

internal fun safeTtsErrorMessage(error: Throwable): String {
    val causes = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
    if (causes.any { it is TTSResponseParsingException }) {
        return "语音服务响应解析失败，请稍后重试。"
    }
    val providerFailure = causes.filterIsInstance<TTSProviderException>().firstOrNull()
    if (providerFailure != null) {
        when (providerFailure.providerCode ?: providerFailure.providerType) {
            TTSProviderErrorCode.PAYMENT_ISSUE,
            TTSProviderErrorCode.PAYMENT_REQUIRED,
            TTSProviderErrorCode.INSUFFICIENT_QUOTA,
            TTSProviderErrorCode.BILLING_HARD_LIMIT_REACHED,
            TTSProviderErrorCode.QUOTA_EXCEEDED -> return "语音服务要求检查账户付款状态或可用额度。"
            TTSProviderErrorCode.RATE_LIMIT_EXCEEDED,
            TTSProviderErrorCode.RATE_LIMITED -> return "语音服务限制了请求频率，请稍后重试。"
            TTSProviderErrorCode.INVALID_API_KEY,
            TTSProviderErrorCode.AUTHENTICATION_ERROR,
            TTSProviderErrorCode.AUTH_ERROR,
            TTSProviderErrorCode.UNAUTHORIZED -> return "语音服务鉴权失败，请检查 API Key 或账号登录状态。"
            TTSProviderErrorCode.PERMISSION_DENIED,
            TTSProviderErrorCode.FORBIDDEN -> return "语音服务拒绝访问，请检查账号权限。"
            TTSProviderErrorCode.MODEL_NOT_FOUND,
            TTSProviderErrorCode.MODEL_UNAVAILABLE -> return "语音服务未找到所选模型，请检查模型配置。"
            TTSProviderErrorCode.VOICE_NOT_FOUND -> return "语音服务未找到所选音色，请检查 Voice ID。"
            TTSProviderErrorCode.PAYLOAD_TOO_LARGE -> return "语音请求内容过大，请缩短文本后重试。"
            TTSProviderErrorCode.INVALID_REQUEST_ERROR,
            TTSProviderErrorCode.INVALID_REQUEST,
            TTSProviderErrorCode.INVALID_PARAMETER,
            TTSProviderErrorCode.BAD_REQUEST -> return "语音服务拒绝了请求，请检查请求参数。"
            null -> Unit
        }
        when (providerFailure.statusCode) {
            400 -> return "语音服务拒绝了请求，请检查请求参数。"
            401 -> return "语音服务鉴权失败，请检查 API Key 或账号登录状态。"
            402 -> return "语音服务要求检查账户付款状态或可用额度。"
            403 -> return "语音服务拒绝访问，请检查账号权限。"
            404 -> return "语音服务未找到请求的资源，请检查模型或音色配置。"
            408 -> return "语音请求超时，请稍后重试。"
            413 -> return "语音请求内容过大，请缩短文本后重试。"
            429 -> return "语音服务限制了请求频率，请稍后重试。"
            in 500..599 -> return "语音服务返回服务端错误，请稍后重试。"
            else -> return providerFailure.statusCode?.let { "语音服务返回 HTTP $it 错误。" }
                ?: "语音服务返回了无法识别的错误，具体原因无法确认。"
        }
    }
    if (causes.any { it is SocketTimeoutException }) return "语音请求超时，请稍后重试。"
    if (causes.any { it is UnknownHostException }) return "无法解析语音服务域名，请检查服务地址和网络连接。"
    if (causes.any { it is SSLException }) return "无法建立语音服务安全连接。"
    if (causes.any { it is ConnectException || it is NoRouteToHostException }) {
        return "无法建立语音服务连接，请检查网络和服务地址。"
    }
    if (causes.any { it is SocketException }) return "语音服务连接中断，具体阶段无法确认。"
    if (causes.any { it is java.io.IOException }) return "语音请求传输过程中断，具体原因无法确认。"
    return "语音合成或播放失败，具体原因无法确认。"
}

private fun safeTtsFailureContext(error: Throwable): String {
    val causes = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
    val types = causes.joinToString(">") { it::class.simpleName ?: "Throwable" }
    val frame = error.stackTrace.firstOrNull()?.let {
        "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
    } ?: "unknown_location"
    val providerFailure = causes.filterIsInstance<TTSProviderException>().firstOrNull()
    val parsingFailure = causes.filterIsInstance<TTSResponseParsingException>().firstOrNull()
    val structured = providerFailure?.let {
        " provider=${it.provider.safeName} status=${it.statusCode ?: "none"}" +
            " code=${it.providerCode?.safeValue ?: "none"} type=${it.providerType?.safeValue ?: "none"}"
    } ?: parsingFailure?.let { " response_parse_provider=${it.provider.safeName}" }.orEmpty()
    return "cause_types=$types at=$frame$structured"
}

private fun logTtsFailure(stage: String, error: Throwable) {
    Log.e(TAG, "$stage ${safeTtsFailureContext(error)}")
}

/**
 * TTS 控制器（重构版）
 * - 负责文本分片、预取合成、排队播放与状态上报
 * - 对外 API 与原版兼容
 */
class TtsController(
    context: Context,
    private val ttsManager: TTSManager
) {
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 组件
    private val chunker = TextChunker(maxChunkLength = 160)
    private val synthesizer = TtsSynthesizer(ttsManager)
    private val audio = AudioPlayer(context)

    // Provider & 作业
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null
    private var captureRealtimeStop: (() -> Unit)? = null
    private var isPaused = false
    private var realtimePreconnectionEnabled = false
    private var realtimePreconnection: RealtimeDialoguePreconnection? = null

    // 队列与缓存（基于稳定 ID）
    private val queue: java.util.concurrent.ConcurrentLinkedQueue<TtsChunk> = java.util.concurrent.ConcurrentLinkedQueue()
    private val cachedAudioQueue: java.util.concurrent.ConcurrentLinkedQueue<TTSResponse> = java.util.concurrent.ConcurrentLinkedQueue()
    private val allChunks: MutableList<TtsChunk> = mutableListOf()
    private val cache = java.util.concurrent.ConcurrentHashMap<UUID, kotlinx.coroutines.Deferred<TTSResponse>>()
    private val audioReadyCallbacks = java.util.concurrent.ConcurrentHashMap<UUID, suspend (TTSResponse) -> Unit>()
    private val audioReadyWithChunkCallbacks = java.util.concurrent.ConcurrentHashMap<UUID, ChunkAudioCallback>()
    private val timingCallbacks = java.util.concurrent.ConcurrentHashMap<UUID, (TtsTimingStage) -> Unit>()
    private var lastPrefetchedIndex: Int = -1

    // 行为参数
    private val chunkDelayMs = 120L
    private val prefetchCount = 4

    // 状态流（保留与旧版兼容的 StateFlow）
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isProviderReady = MutableStateFlow(false)
    val isProviderReady: StateFlow<Boolean> = _isProviderReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    // 统一播放状态（融合音频播放 + 分片进度）
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        // 同步底层播放器状态到统一状态，并补充分片信息
        scope.launch {
            audio.playbackState.collectLatest { audioState ->
                _playbackState.update {
                    audioState.copy(
                        currentChunkIndex = _currentChunk.value,
                        totalChunks = _totalChunks.value,
                        status = if (!_isAvailable.value) PlaybackStatus.Idle else audioState.status
                    )
                }
            }
        }
    }

    /** 选择/取消选择 Provider */
    fun setProvider(provider: TTSProviderSetting?) {
        if (currentProvider != provider) releaseRealtimePreconnection("provider_changed")
        currentProvider = provider
        audio.setVoiceCommunication(realtimePreconnectionEnabled && (provider as? TTSProviderSetting.ElevenLabs)?.fullDuplex == true)
        _isAvailable.update { provider != null }
        _isProviderReady.update { true }
        if (provider == null) stop()
        else prepareRealtimePreconnection()
    }

    /** Enabled only for a connected call, never for ordinary chat or call history. */
    fun setRealtimeDialoguePreconnectionEnabled(enabled: Boolean) {
        realtimePreconnectionEnabled = enabled
        audio.setVoiceCommunication(enabled && (currentProvider as? TTSProviderSetting.ElevenLabs)?.fullDuplex == true)
        if (enabled) prepareRealtimePreconnection()
        else releaseRealtimePreconnection("call_closed")
    }

    private fun prepareRealtimePreconnection() {
        if (!realtimePreconnectionEnabled) return
        val provider = (currentProvider as? TTSProviderSetting.ElevenLabs)?.forRealtimeCall() ?: return
        if (!provider.isElevenLabsV4Family() || provider.apiKey.isBlank() || provider.voiceId.isBlank()) return
        if (realtimePreconnection?.matches(provider) == true) return
        releaseRealtimePreconnection("stale_or_failed")
        Log.i(TAG, "dialogue_preconnection_started model=${provider.model}")
        realtimePreconnection = RealtimeDialoguePreconnection(
            scope = scope,
            provider = provider,
            generateAudio = { text -> ttsManager.generateRealtimeDialogue(provider, text) },
            onFailure = { error ->
                Log.w(TAG, "dialogue_preconnection_failed type=${error.javaClass.simpleName}")
            },
        )
    }

    private fun releaseRealtimePreconnection(reason: String) {
        realtimePreconnection?.let {
            it.close()
            Log.i(TAG, "dialogue_preconnection_released reason=$reason")
        }
        realtimePreconnection = null
    }

    /**
     * 朗读文本
     * - flush=true: 清空当前进度并重新开始
     * - flush=false: 继续队列，追加朗读
     */
    fun speak(
        text: String,
        flush: Boolean = true,
        chunked: Boolean = true,
        onAudioReady: (suspend (TTSResponse) -> Unit)? = null,
        onAudioReadyWithChunk: (suspend (TtsChunk, Int, Int, TTSResponse) -> Unit)? = null,
        emotion: String? = null,
        onTiming: ((TtsTimingStage) -> Unit)? = null,
    ) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val newChunks = if (chunked) {
            chunker.split(text).map { chunk -> chunk.copy(emotion = emotion) }
        } else {
            listOf(TtsChunk(text = text.trim(), index = 0, emotion = emotion))
        }
        if (newChunks.isEmpty()) return

        if (flush) {
            internalReset()
            allChunks.addAll(newChunks)
            queue.addAll(newChunks)
            _currentChunk.update { 0 }
        } else {
            // 追加时，重映射 index 以保持全局顺序
            val startIndex = (allChunks.lastOrNull()?.index ?: -1) + 1
            val remapped = newChunks.mapIndexed { i, c -> c.copy(index = startIndex + i) }
            allChunks.addAll(remapped)
            queue.addAll(remapped)
        }

        onAudioReady?.let { callback ->
            newChunks.forEach { chunk ->
                audioReadyCallbacks[chunk.id] = callback
            }
        }
        onAudioReadyWithChunk?.let { callback ->
            newChunks.forEachIndexed { index, chunk ->
                audioReadyWithChunkCallbacks[chunk.id] = ChunkAudioCallback(
                    callback = callback,
                    chunkIndex = index,
                    totalChunks = newChunks.size,
                )
            }
        }
        onTiming?.let { callback -> newChunks.forEach { timingCallbacks[it.id] = callback } }
        _totalChunks.update { queue.size }
        _error.update { null }

        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                status = PlaybackStatus.Buffering
            )
        }

        if (workerJob?.isActive != true) startWorker()
        prefetchFrom((_currentChunk.value).coerceAtLeast(0))
    }

    fun speakRealtimeDialogue(
        text: Flow<String>,
        onAudioReady: suspend (TTSResponse) -> Unit,
        onAlignment: ((String) -> Unit)? = null,
        onTiming: ((TtsTimingStage) -> Unit)? = null,
    ) {
        val provider = (currentProvider as? TTSProviderSetting.ElevenLabs)?.forRealtimeCall()
        if (provider?.isElevenLabsV4Family() != true) {
            _error.update { "Realtime dialogue requires ElevenLabs v4" }
            _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = _error.value) }
            return
        }

        val prepared = realtimePreconnection?.takeIf { it.matches(provider) }
        if (prepared == null) releaseRealtimePreconnection("unavailable_at_reply")
        realtimePreconnection = null
        Log.i(TAG, "dialogue_reply_connection reused=${prepared != null} model=${provider.model}")
        internalReset()
        _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
        val capture = RealtimeDialogueCapture()
        var interruptedAtMs: Long? = null
        captureRealtimeStop = { interruptedAtMs = audio.positionMs }
        workerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            _isSpeaking.update { true }
            try {
                var claimedConnection = prepared
                val audioFlow = recoverRealtimeDialogue(
                    text = text,
                    connect = { input ->
                        val connection = claimedConnection
                        claimedConnection = null
                        connection?.audioFor(input) ?: ttsManager.generateRealtimeDialogue(provider, input)
                    },
                    fallback = if (provider.fullDuplex) { script ->
                        Log.w(TAG, "Dialogue unavailable before audio; using existing v4 HTTP provider")
                        ttsManager.generateStreamingSpeech(
                            provider.copy(model = "eleven_v4", fullDuplex = false),
                            TTSRequest(text = script),
                        )
                    } else null,
                ).onEach { chunk ->
                    capture.append(chunk)?.let { onAlignment?.invoke(it) }
                }
                audio.play(audioFlow, onTiming)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                interruptedAtMs = audio.positionMs
                logTtsFailure("Realtime dialogue playback error", error)
                _error.update { safeTtsErrorMessage(error) }
                _playbackState.update {
                    it.copy(status = PlaybackStatus.Error, errorMessage = _error.value)
                }
            } finally {
                // Saving an interrupted prefix never resumes playback or mutates the next worker.
                runCatching {
                    withContext(NonCancellable) {
                        withTimeout(15_000) { onAudioReady(capture.snapshot(interruptedAtMs)) }
                    }
                }.onFailure { Log.w(TAG, "Dialogue audio capture could not be saved", it) }
                if (isActive) {
                    captureRealtimeStop = null
                    _isSpeaking.update { false }
                    if (_playbackState.value.status != PlaybackStatus.Error) {
                        _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                    }
                    prepareRealtimePreconnection()
                }
            }
        }
        workerJob?.invokeOnCompletion { prepared?.close() }
    }

    private fun internalReset() {
        // Reset current session while keeping provider availability
        captureRealtimeStop?.invoke()
        captureRealtimeStop = null
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        cachedAudioQueue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Reset")) }
        cache.clear()
        audioReadyCallbacks.clear()
        audioReadyWithChunkCallbacks.clear()
        timingCallbacks.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _error.update { null }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 暂停播放（保留进度） */
    fun pause() {
        isPaused = true
        audio.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    fun playCachedAudio(
        response: TTSResponse,
        startPositionMs: Long? = null,
        endPositionMs: Long? = null,
    ) {
        if (startPositionMs == null && endPositionMs == null) {
            playCachedAudioSequence(listOf(response), flush = true)
            return
        }
        stop()
        workerJob = scope.launch {
            _isSpeaking.update { true }
            try {
                audio.play(response, startPositionMs, endPositionMs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logTtsFailure("Cached audio playback error", error)
                _error.update { safeTtsErrorMessage(error) }
                _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = _error.value) }
            } finally {
                _isSpeaking.update { false }
            }
        }
    }

    fun playCachedAudioSequence(responses: List<TTSResponse>, flush: Boolean = true) {
        if (responses.isEmpty()) return
        if (flush) stop()
        cachedAudioQueue.addAll(responses)
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            _isSpeaking.update { true }
            try {
                while (isActive) {
                    val response = cachedAudioQueue.poll() ?: break
                    audio.play(response)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logTtsFailure("Cached audio sequence playback error", e)
                _error.update { safeTtsErrorMessage(e) }
            } finally {
                _isSpeaking.update { false }
            }
        }
    }

    /** 恢复播放 */
    fun resume() {
        isPaused = false
        audio.resume()
        _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
    }

    /** 快进当前音频 */
    fun fastForward(ms: Long = 5_000) {
        audio.seekBy(ms)
    }

    /** 设置播放速度 */
    fun setSpeed(speed: Float) {
        audio.setSpeed(speed)
    }

    /** 跳过下一段（不打断当前正在播放） */
    fun skipNext() {
        if (queue.isNotEmpty()) {
            queue.poll()
            _totalChunks.update { queue.size }
        }
    }

    /** 停止并清空状态 */
    fun stop() {
        captureRealtimeStop?.invoke()
        captureRealtimeStop = null
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        cachedAudioQueue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Stopped")) }
        cache.clear()
        audioReadyCallbacks.clear()
        audioReadyWithChunkCallbacks.clear()
        timingCallbacks.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
        // Stopping a reply for user input keeps the next, text-free connection ready.
        prepareRealtimePreconnection()
    }

    /** 释放资源 */
    fun dispose() {
        setRealtimeDialoguePreconnectionEnabled(false)
        stop()
        _isProviderReady.update { false }
        scope.cancel()
        audio.release()
    }

    // region 内部：播放调度
    private fun startWorker() {
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        workerJob = scope.launch {
            _isSpeaking.update { true }
            var processedCount = _currentChunk.value
            try {
                while (isActive) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val chunk = queue.poll() ?: break

                    // 更新状态（1-based）
                    _currentChunk.update { processedCount + 1 }
                    _totalChunks.update { queue.size + 1 }
                    _playbackState.update {
                        it.copy(
                            currentChunkIndex = _currentChunk.value,
                            totalChunks = _totalChunks.value
                        )
                    }

                    // 预取下一窗口
                    prefetchFrom(chunk.index + 1)

                    val shouldStream = ttsManager.supportsStreaming(provider)
                    val onTiming = timingCallbacks.remove(chunk.id)

                    // 播放
                    try {
                        if (shouldStream) {
                            var format: me.rerere.tts.model.AudioFormat? = null
                            var sampleRate: Int? = null
                            val captured = ByteArrayOutputStream()
                            val capturedFlow = synthesizer.synthesizeStreaming(provider, chunk).onEach { audioChunk ->
                                if (format == null) format = audioChunk.format
                                if (sampleRate == null) sampleRate = audioChunk.sampleRate
                                captured.write(audioChunk.data)
                            }
                            audio.play(capturedFlow, onTiming)
                            val response = TTSResponse(
                                audioData = captured.toByteArray(),
                                format = format ?: me.rerere.tts.model.AudioFormat.MP3,
                                sampleRate = sampleRate,
                            )
                            audioReadyCallbacks.remove(chunk.id)?.invoke(response)
                            audioReadyWithChunkCallbacks.remove(chunk.id)?.let { callback ->
                                callback.callback(chunk, callback.chunkIndex, callback.totalChunks, response)
                            }
                        } else {
                            val response = awaitOrCreate(chunk, provider)
                            if (response.audioData.isNotEmpty()) onTiming?.invoke(TtsTimingStage.FirstAudio)
                            audioReadyCallbacks.remove(chunk.id)?.invoke(response)
                            audioReadyWithChunkCallbacks.remove(chunk.id)?.let { callback ->
                                callback.callback(chunk, callback.chunkIndex, callback.totalChunks, response)
                            }
                            audio.play(response, onTiming = onTiming)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logTtsFailure("Playback error", e)
                        _error.update { safeTtsErrorMessage(e) }
                    }

                    if (queue.isNotEmpty()) delay(chunkDelayMs)

                    processedCount++
                }
            } finally {
                if (isActive) _isSpeaking.update { false }
                if (isActive && queue.isEmpty()) {
                    _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                }
            }
        }
    }

    private fun prefetchFrom(startIndex: Int) {
        val provider = currentProvider ?: return
        if (ttsManager.supportsStreaming(provider)) return
        val begin = startIndex.coerceAtLeast(lastPrefetchedIndex + 1)
        val endExclusive = (begin + prefetchCount).coerceAtMost(allChunks.size)
        if (begin >= endExclusive) return

        for (i in begin until endExclusive) {
            val chunk = allChunks.getOrNull(i) ?: continue
            cache.computeIfAbsent(chunk.id) {
                scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
            }
        }
        lastPrefetchedIndex = endExclusive - 1
    }

    private suspend fun awaitOrCreate(chunk: TtsChunk, provider: TTSProviderSetting): TTSResponse {
        val deferred = cache.computeIfAbsent(chunk.id) {
            scope.async(Dispatchers.IO) { synthesizer.synthesize(provider, chunk) }
        }
        return try {
            deferred.await()
        } finally {
            // 可按需保留缓存（此处保留，便于重播/重试）
        }
    }

    private data class ChunkAudioCallback(
        val callback: suspend (TtsChunk, Int, Int, TTSResponse) -> Unit,
        val chunkIndex: Int,
        val totalChunks: Int,
    )
    // endregion
}
