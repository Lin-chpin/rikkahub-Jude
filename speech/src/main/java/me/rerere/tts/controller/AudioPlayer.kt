package me.rerere.tts.controller

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class AudioPlayer(context: Context) {
    private companion object {
        const val TAG = "AudioPlayer"
    }

    private val player = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    250,
                    1_000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null
    private var playbackGeneration = 0L

    fun setVoiceCommunication(enabled: Boolean) {
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(if (enabled) C.USAGE_VOICE_COMMUNICATION else C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            false,
        )
    }

    val positionMs: Long get() = player.currentPosition.coerceAtLeast(0L)
    fun pause() = player.pause()
    fun resume() = player.play()
    fun stop() {
        playbackGeneration++
        stopPositionUpdates()
        player.stop()
    }
    fun clear() = player.clearMediaItems()
    fun release() = player.release()
    fun seekBy(ms: Long) = player.seekTo(player.currentPosition + ms)
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _playbackState.update { it.copy(speed = speed) }
    }

    @OptIn(UnstableApi::class)
    suspend fun play(
        response: TTSResponse,
        startPositionMs: Long? = null,
        endPositionMs: Long? = null,
    ) {
        val bytes = if (response.format == AudioFormat.PCM) {
            pcmToWav(response.audioData, response.sampleRate ?: 24000)
        } else response.audioData
        Log.i(TAG, "Preparing audio: bytes=" + bytes.size + ", format=" + response.format + ", sampleRate=" + response.sampleRate)

        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val clipStart = startPositionMs?.coerceAtLeast(0L) ?: 0L
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
        val clippedSource = if (startPositionMs != null || endPositionMs != null) {
            require(endPositionMs == null || endPositionMs > clipStart) { "Invalid audio clip range" }
            Log.i(TAG, "Cached audio clip: startMs=$clipStart endMs=$endPositionMs")
            // setMediaSource bypasses ExoPlayer's factory, so clipping must wrap the source itself.
            ClippingMediaSource.Builder(mediaSource)
                .setStartPositionMs(clipStart)
                .apply { endPositionMs?.let(::setEndPositionMs) }
                // Streaming MP3 captures can lack a seek index; short in-memory call clips can decode the prefix.
                .setAllowUnseekableMedia(true)
                .build()
        } else mediaSource
        val durationMs = endPositionMs?.minus(clipStart) ?: response.duration?.times(1000)?.toLong()
        playMediaSource(clippedSource, durationMs)
    }

    @OptIn(UnstableApi::class)
    suspend fun play(stream: Flow<AudioChunk>) = coroutineScope {
        val streamTiming = StreamTiming()
        val dataSourceFactory = DataSource.Factory { FlowDataSource(stream, streamTiming, this) }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
        playMediaSource(mediaSource, null, streamTiming)
    }

    private suspend fun playMediaSource(
        mediaSource: androidx.media3.exoplayer.source.MediaSource,
        durationMs: Long?,
        streamTiming: StreamTiming? = null,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val generation = ++playbackGeneration
        Log.i(TAG, "Preparing ExoPlayer media source: durationMs=$durationMs startBufferMs=250")
        _playbackState.update {
            it.copy(
                status = PlaybackStatus.Buffering,
                positionMs = 0L,
                durationMs = durationMs ?: it.durationMs
            )
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (generation != playbackGeneration) return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                        streamTiming?.buffering(player.currentPosition)
                        stopPositionUpdates()
                    }
                    Player.STATE_READY -> {
                        val isPlaying = player.isPlaying
                        val duration = if (player.duration > 0) player.duration else playbackState.value.durationMs
                        _playbackState.update {
                            it.copy(
                                status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused,
                                durationMs = duration,
                                positionMs = player.currentPosition
                            )
                        }
                        Log.i(TAG, "Audio playback ready: isPlaying=" + isPlaying + ", volume=" + player.volume + ", durationMs=" + duration)
                        streamTiming?.playerReady()
                        if (isPlaying) streamTiming?.playbackStarted()
                        if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                    }
                    Player.STATE_ENDED -> {
                        stopPositionUpdates()
                        _playbackState.update {
                            it.copy(
                                status = PlaybackStatus.Ended,
                                positionMs = player.duration.coerceAtLeast(it.positionMs),
                                durationMs = if (player.duration > 0) player.duration else it.durationMs
                            )
                        }
                        Log.i(TAG, "Audio playback ended")
                        streamTiming?.playbackEnded()
                        player.removeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    Player.STATE_IDLE -> {
                        stopPositionUpdates()
                        _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (generation != playbackGeneration) return
                Log.e(TAG, "Audio playback error: code=" + error.errorCode + ", message=" + error.message, error)
                streamTiming?.playbackFailed(error.errorCode)
                player.removeListener(this)
                stopPositionUpdates()
                _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = error.message) }
                if (cont.isActive) cont.resumeWithException(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (generation != playbackGeneration) return
                // isPlaying=false also follows buffering/end/stop; preserve those terminal and loading states.
                if (isPlaying || player.playbackState == Player.STATE_READY) {
                    val status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused
                    _playbackState.update { it.copy(status = status) }
                }
                if (isPlaying) {
                    streamTiming?.playbackStarted()
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                }
            }
        }
        player.addListener(listener)

        // Register before prepare/play. ExoPlayer can reach READY immediately on
        // the main looper; registering afterwards leaves callers stuck in Buffering.
        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()

        cont.invokeOnCancellation {
            player.removeListener(listener)
            if (generation == playbackGeneration) {
                player.stop()
                stopPositionUpdates()
            }
            streamTiming?.playbackCancelled()
        }
    }

    private class FlowDataSource(
        private val audioFlow: Flow<AudioChunk>,
        private val streamTiming: StreamTiming,
        private val producerScope: CoroutineScope,
    ) : DataSource {
        private var input: java.io.PipedInputStream? = null
        private var output: java.io.PipedOutputStream? = null
        private var producerJob: Job? = null
        @Volatile private var producerError: Throwable? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            streamTiming.opened()
            val pipeInput = java.io.PipedInputStream(64 * 1024)
            val pipeOutput = java.io.PipedOutputStream(pipeInput)
            input = pipeInput
            output = pipeOutput
            producerJob = producerScope.launch(Dispatchers.IO) {
                try {
                    audioFlow.collect { chunk ->
                        streamTiming.chunkReceived(chunk)
                        pipeOutput.write(chunk.data)
                        pipeOutput.flush()
                    }
                } catch (error: Throwable) {
                    producerError = error
                } finally {
                    runCatching { pipeOutput.close() }
                }
            }
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val pipeInput = input ?: error("Streaming audio data source is not open")
            val bytesRead = pipeInput.read(buffer, offset, length)
            if (bytesRead < 0) {
                producerError?.let { throw java.io.IOException("TTS stream failed", it) }
                return C.RESULT_END_OF_INPUT
            }
            streamTiming.bytesRead(bytesRead)
            return bytesRead
        }

        override fun getUri(): Uri? = null

        override fun close() {
            producerJob?.cancel()
            producerJob = null
            runCatching { output?.close() }
            runCatching { input?.close() }
            output = null
            input = null
        }
    }

    private class StreamTiming {
        private var traceId = "unknown"
        private var openedAtNanos = 0L
        private var firstChunkLogged = false
        private var firstReadLogged = false
        private var playerReadyLogged = false
        private var playbackStartedLogged = false
        private var playbackEndedLogged = false
        private var playbackFailedLogged = false
        private var playbackCancelledLogged = false

        @Synchronized
        fun opened() {
            if (openedAtNanos == 0L) openedAtNanos = System.nanoTime()
        }

        @Synchronized
        fun chunkReceived(chunk: AudioChunk) {
            if (traceId == "unknown") traceId = chunk.metadata["timing_id"] ?: traceId
            if (!firstChunkLogged && chunk.data.isNotEmpty() && traceId != "unknown") {
                firstChunkLogged = true
                Log.i(TAG, "tts_timing trace=$traceId stage=audio_chunk_to_player bytes=${chunk.data.size}")
            }
        }

        @Synchronized
        fun bytesRead(bytes: Int) {
            if (!firstReadLogged && bytes > 0 && traceId != "unknown") {
                firstReadLogged = true
                Log.i(TAG, "tts_timing trace=$traceId stage=first_audio_bytes_read elapsedSinceStreamOpenMs=${(System.nanoTime() - openedAtNanos) / 1_000_000} bytes=$bytes")
            }
        }

        @Synchronized
        fun buffering(positionMs: Long) {
            Log.i(TAG, "tts_timing trace=$traceId stage=buffering positionMs=$positionMs " +
                "elapsedSinceStreamOpenMs=${if (openedAtNanos == 0L) 0 else (System.nanoTime() - openedAtNanos) / 1_000_000}")
        }

        @Synchronized
        fun playerReady() {
            if (!playerReadyLogged && traceId != "unknown") {
                playerReadyLogged = true
                Log.i(TAG, "tts_timing trace=$traceId stage=player_ready elapsedSinceStreamOpenMs=${(System.nanoTime() - openedAtNanos) / 1_000_000}")
            }
        }

        @Synchronized
        fun playbackStarted() {
            if (!playbackStartedLogged && traceId != "unknown") {
                playbackStartedLogged = true
                Log.i(TAG, "tts_timing trace=$traceId stage=playback_started elapsedSinceStreamOpenMs=${(System.nanoTime() - openedAtNanos) / 1_000_000}")
            }
        }

        @Synchronized
        fun playbackEnded() {
            if (!playbackEndedLogged && traceId != "unknown") {
                playbackEndedLogged = true
                Log.i(TAG, "tts_timing trace=$traceId stage=playback_ended elapsedSinceStreamOpenMs=${(System.nanoTime() - openedAtNanos) / 1_000_000}")
            }
        }

        @Synchronized
        fun playbackFailed(errorCode: Int) {
            if (!playbackFailedLogged && traceId != "unknown") {
                playbackFailedLogged = true
                Log.i(TAG, "tts_timing trace=$traceId stage=playback_failed elapsedSinceStreamOpenMs=${(System.nanoTime() - openedAtNanos) / 1_000_000} errorCode=$errorCode")
            }
        }

        @Synchronized
        fun playbackCancelled() {
            if (!playbackCancelledLogged && traceId != "unknown") {
                playbackCancelledLogged = true
                Log.i(TAG, "tts_timing trace=$traceId stage=playback_cancelled elapsedSinceStreamOpenMs=${(System.nanoTime() - openedAtNanos) / 1_000_000}")
            }
        }
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                _playbackState.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = if (player.duration > 0) player.duration else it.durationMs
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(1))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}

