package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderErrorCode
import me.rerere.tts.provider.TTSProviderKind
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.TTSResponseParsingException
import me.rerere.tts.provider.createTTSProviderHttpException
import me.rerere.tts.provider.parseTTSProviderErrorDetails
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val FISH_AUDIO_BASE_URL = "https://api.fish.audio"
private const val FISH_AUDIO_TTS_PATH = "/v1/tts"
private const val FISH_AUDIO_LIVE_URL = "wss://api.fish.audio/v1/tts/live"

private val fishAudioHttpClient = OkHttpClient.Builder()
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

private val fishAudioLiveClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

class FishAudioTTSProvider : TTSProvider<TTSProviderSetting.FishAudio> {
    override fun supportsStreaming(providerSetting: TTSProviderSetting.FishAudio) = true

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.FishAudio,
        request: TTSRequest,
    ): Flow<AudioChunk> = httpSpeech(providerSetting, request, streaming = false)

    override fun generateStreamingSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.FishAudio,
        request: TTSRequest,
    ): Flow<AudioChunk> = httpSpeech(providerSetting, request, streaming = true)
}

private fun httpSpeech(
    setting: TTSProviderSetting.FishAudio,
    request: TTSRequest,
    streaming: Boolean,
): Flow<AudioChunk> = flow {
    val apiKey = setting.apiKey.trim()
    val voiceId = setting.voiceId.trim()
    require(apiKey.isNotEmpty()) { "Fish Audio API key is required" }
    require(voiceId.isNotEmpty()) { "Fish Audio voice ID is required" }
    require(setting.model in TTSProviderSetting.FishAudio.MODEL_OPTIONS) { "Fish Audio model is not supported" }

    val body = JSONObject()
        .put("text", request.text)
        .put("reference_id", voiceId)
        .put("format", "mp3")
        .put("prosody", JSONObject().put("speed", setting.speed.coerceIn(
            TTSProviderSetting.FishAudio.MIN_SPEED,
            TTSProviderSetting.FishAudio.MAX_SPEED,
        )))
        .toString()
        .toRequestBody("application/json".toMediaType())
    val httpRequest = Request.Builder()
        .url(FISH_AUDIO_BASE_URL + FISH_AUDIO_TTS_PATH)
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .header("model", setting.model)
        .post(body)
        .build()
    coroutineScope {
        val call = fishAudioHttpClient.newCall(httpRequest)
        val cancellation = launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw createTTSProviderHttpException(
                        TTSProviderKind.FISH_AUDIO,
                        response.code,
                        response.body.string(),
                    )
                }
                val responseBody = response.body
                val metadata = mapOf(
                    "provider" to "fish_audio",
                    "model" to setting.model,
                    "voice_id" to voiceId,
                )
                if (streaming) {
                    responseBody.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) emit(AudioChunk(buffer.copyOf(count), AudioFormat.MP3, metadata = metadata))
                        }
                    }
                } else {
                    emit(AudioChunk(responseBody.bytes(), AudioFormat.MP3, isLast = true, metadata = metadata))
                }
            }
        } finally {
            cancellation.cancel()
        }
    }
}

internal object FishAudioRealtimeTTS {
    fun generate(setting: TTSProviderSetting.FishAudio, text: Flow<String>): Flow<AudioChunk> = callbackFlow {
        val output = this
        val apiKey = setting.apiKey.trim()
        val voiceId = setting.voiceId.trim()
        require(apiKey.isNotEmpty()) { "Fish Audio API key is required" }
        require(voiceId.isNotEmpty()) { "Fish Audio voice ID is required" }
        require(setting.model in TTSProviderSetting.FishAudio.MODEL_OPTIONS) { "Fish Audio model is not supported" }

        val ready = CompletableDeferred<WebSocket>()
        var finished = false
        val request = Request.Builder()
            .url(FISH_AUDIO_LIVE_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("model", setting.model)
            .build()
        val socket = fishAudioLiveClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val started = webSocket.send(
                    encodeFishAudioEvent(mapOf(
                        "event" to "start",
                        "request" to mapOf(
                            "text" to "",
                            "reference_id" to voiceId,
                            "format" to "mp3",
                            "latency" to "balanced",
                            "prosody" to mapOf("speed" to setting.speed.coerceIn(
                                TTSProviderSetting.FishAudio.MIN_SPEED,
                                TTSProviderSetting.FishAudio.MAX_SPEED,
                            )),
                        ),
                    )).toByteString(),
                )
                if (started) ready.complete(webSocket)
                else {
                    val error = IOException("Fish Audio rejected the live TTS session")
                    ready.completeExceptionally(error)
                    output.close(error)
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val event = runCatching { decodeFishAudioEvent(bytes.toByteArray()) }
                    .getOrElse {
                        output.close(TTSResponseParsingException(TTSProviderKind.FISH_AUDIO, it))
                        return
                    }
                when (event["event"] as? String) {
                    "audio" -> {
                        val audio = event["audio"] as? ByteArray
                        if (audio != null && audio.isNotEmpty()) {
                            val result = output.trySendBlocking(
                                AudioChunk(
                                    data = audio,
                                    format = AudioFormat.MP3,
                                    metadata = mapOf(
                                        "provider" to "fish_audio",
                                        "model" to setting.model,
                                        "voice_id" to voiceId,
                                    ),
                                ),
                            )
                            if (result.isFailure) output.close(result.exceptionOrNull() ?: IOException("Fish Audio consumer closed"))
                        }
                    }

                    "finish" -> {
                        if ((event["reason"] as? String)?.equals("error", ignoreCase = true) == true) {
                            output.close(TTSProviderException(provider = TTSProviderKind.FISH_AUDIO))
                        } else {
                            finished = true
                            output.close()
                        }
                    }

                    "error" -> {
                        val error = event["error"]
                        val details = error as? Map<*, *>
                        output.close(TTSProviderException(
                            provider = TTSProviderKind.FISH_AUDIO,
                            providerCode = TTSProviderErrorCode.fromUntrusted(
                                details?.get("code") as? String ?: error as? String,
                            ),
                        ))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val details = parseTTSProviderErrorDetails(text)
                output.close(TTSProviderException(
                    provider = TTSProviderKind.FISH_AUDIO,
                    providerCode = details.code,
                    providerType = details.type,
                ))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = response?.let {
                    createTTSProviderHttpException(TTSProviderKind.FISH_AUDIO, it.code, runCatching { it.body.string() }.getOrNull())
                } ?: t
                ready.completeExceptionally(error)
                output.close(error)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (finished) output.close()
                else output.close(IOException("Fish Audio live TTS closed before completion ($code)"))
            }
        })

        val sender = launch {
            try {
                val activeSocket = kotlinx.coroutines.withTimeout(10_000) { ready.await() }
                text.collect { delta ->
                    if (delta.isEmpty()) return@collect
                    if (!activeSocket.send(encodeFishAudioEvent(mapOf("event" to "text", "text" to delta)).toByteString())) {
                        throw IOException("Fish Audio live TTS stopped accepting text")
                    }
                    if (delta.any { it in "。！？!?\n" }) {
                        if (!activeSocket.send(encodeFishAudioEvent(mapOf("event" to "flush")).toByteString())) {
                            throw IOException("Fish Audio live TTS could not flush text")
                        }
                    }
                }
                activeSocket.send(encodeFishAudioEvent(mapOf("event" to "flush")).toByteString())
                if (!activeSocket.send(encodeFishAudioEvent(mapOf("event" to "stop")).toByteString())) {
                    throw IOException("Fish Audio live TTS could not finish the text stream")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                output.close(error)
            }
        }
        awaitClose {
            sender.cancel()
            socket.cancel()
        }
    }
}

// Fish live TTS frames use MessagePack; this codec handles only its event maps and fields.
internal fun encodeFishAudioEvent(value: Map<String, Any>): ByteArray {
    // Fish Audio uses MessagePack frames on this socket; keep this codec limited to its event maps.
    val output = ByteArrayOutputStream()
    fun writeMapLength(length: Int) {
        when {
            length < 16 -> output.write(0x80 or length)
            length < 65_536 -> { output.write(0xde); output.write(length ushr 8); output.write(length) }
            else -> {
                output.write(0xdf)
                output.write(length ushr 24); output.write(length ushr 16); output.write(length ushr 8); output.write(length)
            }
        }
    }
    fun writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        when {
            bytes.size < 32 -> output.write(0xa0 or bytes.size)
            bytes.size < 256 -> { output.write(0xd9); output.write(bytes.size) }
            bytes.size < 65_536 -> { output.write(0xda); output.write(bytes.size ushr 8); output.write(bytes.size) }
            else -> { output.write(0xdb); output.write(bytes.size ushr 24); output.write(bytes.size ushr 16); output.write(bytes.size ushr 8); output.write(bytes.size) }
        }
        output.write(bytes)
    }
    fun writeValue(value: Any) {
        when (value) {
            is String -> writeString(value)
            is ByteArray -> {
                when {
                    value.size < 256 -> { output.write(0xc4); output.write(value.size) }
                    value.size < 65_536 -> { output.write(0xc5); output.write(value.size ushr 8); output.write(value.size) }
                    else -> { output.write(0xc6); output.write(value.size ushr 24); output.write(value.size ushr 16); output.write(value.size ushr 8); output.write(value.size) }
                }
                output.write(value)
            }
            is Number -> {
                output.write(0xcb)
                val bits = java.lang.Double.doubleToLongBits(value.toDouble())
                for (shift in 56 downTo 0 step 8) output.write((bits ushr shift).toInt())
            }
            is Map<*, *> -> {
                val entries = value.entries.toList()
                writeMapLength(entries.size)
                entries.forEach { (key, child) -> writeString(key as String); writeValue(child!!) }
            }
            else -> error("Unsupported Fish Audio event value")
        }
    }
    writeMapLength(value.size)
    value.forEach { (key, child) -> writeString(key); writeValue(child) }
    return output.toByteArray()
}

internal fun decodeFishAudioEvent(bytes: ByteArray): Map<String, Any?> {
    var offset = 0
    fun byte(): Int = bytes.getOrNull(offset++)?.toInt()?.and(0xff) ?: throw IOException("Truncated Fish Audio message")
    fun unsigned(count: Int): Long {
        var value = 0L
        repeat(count) { value = (value shl 8) or byte().toLong() }
        return value
    }
    fun chunk(length: Long): ByteArray {
        if (length !in 0..(20L * 1024 * 1024) || length > bytes.size - offset) throw IOException("Invalid Fish Audio message length")
        return bytes.copyOfRange(offset, offset + length.toInt()).also { offset += length.toInt() }
    }
    fun readString(length: Long) = chunk(length).toString(Charsets.UTF_8)
    fun read(): Any? {
        val marker = byte()
        return when {
            marker in 0xa0..0xbf -> readString((marker and 0x1f).toLong())
            marker == 0xc4 -> chunk(unsigned(1))
            marker == 0xc5 -> chunk(unsigned(2))
            marker == 0xc6 -> chunk(unsigned(4))
            marker == 0xcb -> Double.fromBits(unsigned(8))
            marker == 0xd9 -> readString(unsigned(1))
            marker == 0xda -> readString(unsigned(2))
            marker == 0xdb -> readString(unsigned(4))
            marker in 0x80..0x8f -> {
                val count = marker and 0x0f
                if (count > 32) throw IOException("Fish Audio event has too many fields")
                List(count) { read() to read() }.toMap()
            }
            marker == 0xde -> {
                val count = unsigned(2)
                if (count > 32) throw IOException("Fish Audio event has too many fields")
                List(count.toInt()) { read() to read() }.toMap()
            }
            marker == 0xdf -> {
                val count = unsigned(4)
                if (count > 32) throw IOException("Fish Audio event has too many fields")
                List(count.toInt()) { read() to read() }.toMap()
            }
            else -> throw IOException("Unsupported Fish Audio message marker")
        }
    }
    val decoded = read() as? Map<*, *> ?: throw IOException("Fish Audio sent an invalid event")
    return decoded.entries.associate { (key, value) ->
        (key as? String ?: throw IOException("Fish Audio event has a non-string field name")) to value
    }
}
