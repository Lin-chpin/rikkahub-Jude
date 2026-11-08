package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderKind
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.TTSResponseParsingException
import me.rerere.tts.provider.createTTSProviderSseFailure
import me.rerere.tts.provider.isSpeech26Model
import me.rerere.tts.provider.isSpeech28Model
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "MiniMaxTTSProvider"

@Serializable
private data class MiniMaxResponseData(
    val audio: String,
    val status: Int,
    val ced: String
)

@Serializable
private data class MiniMaxResponse(
    val data: MiniMaxResponseData
)

internal data class MiniMaxAudioChunk(
    val audioBytes: ByteArray,
    val status: Int,
    val ced: String,
)

internal fun decodeMiniMaxAudioChunk(data: String): MiniMaxAudioChunk {
    try {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<MiniMaxResponse>(data).data
        val audioBytes = hexStringToBytes(decoded.audio)
        if (audioBytes.isEmpty()) error("MiniMax response contained empty audio data")
        return MiniMaxAudioChunk(audioBytes, decoded.status, decoded.ced)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        if (error is TTSResponseParsingException) throw error
        throw TTSResponseParsingException(TTSProviderKind.MINIMAX, error)
    }
}

class MiniMaxTTSProvider : TTSProvider<TTSProviderSetting.MiniMax> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun supportsStreaming(providerSetting: TTSProviderSetting.MiniMax): Boolean {
        return providerSetting.isSpeech26Model() || providerSetting.isSpeech28Model()
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiniMax,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val apiKey = providerSetting.apiKey.trim()
        val baseUrl = providerSetting.baseUrl.trim().trimEnd('/')
        val model = providerSetting.model.trim()
        val voiceId = providerSetting.voiceId.trim()
        val timingId = System.nanoTime().toString(16)
        require(apiKey.isNotEmpty()) { "MiniMax API key is required" }
        require(baseUrl.isNotEmpty()) { "MiniMax base URL is required" }
        require(model.isNotEmpty()) { "MiniMax model is required" }
        require(request.text.isNotBlank()) { "MiniMax text is required" }
        require(voiceId.isNotEmpty()) { "MiniMax voice ID is required" }
        require(providerSetting.speed in TTSProviderSetting.MiniMax.MIN_SPEED..TTSProviderSetting.MiniMax.MAX_SPEED) {
            "MiniMax speed must be between ${TTSProviderSetting.MiniMax.MIN_SPEED} and ${TTSProviderSetting.MiniMax.MAX_SPEED}"
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("text", request.text)
            put("stream", true)
            put("output_format", "hex")
            put("stream_options", buildJsonObject {
                put("exclude_aggregated_audio", true)
            })
            providerSetting.languageBoost
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("language_boost", it) }
            put("voice_setting", buildJsonObject {
                put("voice_id", voiceId)
                put("speed", providerSetting.speed)
                (request.emotion ?: providerSetting.emotion)
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty() &&
                            (providerSetting.isSpeech26Model() || providerSetting.isSpeech28Model())
                    }
                    ?.let { put("emotion", it) }
            })
        }

        val requestStartedAtNanos = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - requestStartedAtNanos) / 1_000_000
        Log.i(TAG, "tts_timing trace=$timingId stage=request_started elapsedMs=${elapsedMs()} model=$model chars=${request.text.length}")

        val httpRequest = Request.Builder()
            .url("$baseUrl/t2a_v2")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        var hasEmittedAudio = false
        var firstAudioReceived = false
        var audioChunkCount = 0
        var totalAudioBytes = 0L

        httpClient.sseFlow(httpRequest).collect {
            when (it) {
                is SseEvent.Open -> Log.i(TAG, "tts_timing trace=$timingId stage=sse_open elapsedMs=${elapsedMs()}")
                is SseEvent.Event -> {
                    val chunk = try {
                        decodeMiniMaxAudioChunk(it.data)
                    } catch (error: TTSResponseParsingException) {
                        Log.e(TAG, "TTS response parsing failed: provider=minimax cause_type=${error.cause?.javaClass?.simpleName ?: "unknown"}")
                        throw error
                    }
                    if (!firstAudioReceived) {
                        firstAudioReceived = true
                        Log.i(TAG, "tts_timing trace=$timingId stage=first_audio_bytes_received elapsedMs=${elapsedMs()} bytes=${chunk.audioBytes.size}")
                    }
                    audioChunkCount++
                    totalAudioBytes += chunk.audioBytes.size
                    emit(
                        AudioChunk(
                            data = chunk.audioBytes,
                            format = AudioFormat.MP3,
                            sampleRate = 32000,
                            isLast = false,
                            metadata = mapOf(
                                "provider" to "minimax",
                                "model" to model,
                                "voice" to voiceId,
                                "status" to chunk.status.toString(),
                                "ced" to chunk.ced,
                                "timing_id" to timingId,
                            )
                        )
                    )
                    hasEmittedAudio = true
                }

                is SseEvent.Closed -> {
                    Log.i(TAG, "tts_timing trace=$timingId stage=sse_closed elapsedMs=${elapsedMs()} chunks=$audioChunkCount bytes=$totalAudioBytes")
                    // Emit final chunk if we haven't already
                    if (hasEmittedAudio) {
                        emit(
                            AudioChunk(
                                data = byteArrayOf(), // Empty data for last chunk
                                format = AudioFormat.MP3,
                                sampleRate = 32000,
                                isLast = true,
                                metadata = mapOf("provider" to "minimax")
                            )
                        )
                    } else {
                        throw TTSResponseParsingException(TTSProviderKind.MINIMAX)
                    }
                }

                is SseEvent.Failure -> {
                    if (it.responseFormatInvalid) {
                        throw TTSResponseParsingException(TTSProviderKind.MINIMAX, it.throwable)
                    }
                    val failure = createTTSProviderSseFailure(
                        TTSProviderKind.MINIMAX,
                        it.response?.takeIf { response -> !response.isSuccessful }?.code,
                        it.throwable,
                        it.safeErrorCode,
                        it.safeErrorType,
                    )
                    val causeTypes = generateSequence(failure as Throwable?) { it.cause }
                        .take(4)
                        .joinToString(">") { it::class.simpleName ?: "Throwable" }
                    Log.i(TAG, "tts_timing trace=$timingId stage=sse_failed elapsedMs=${elapsedMs()} chunks=$audioChunkCount bytes=$totalAudioBytes cause_types=$causeTypes")
                    Log.e(TAG, "TTS stream failure type=${failure::class.simpleName}" +
                        (it.response?.takeIf { response -> !response.isSuccessful }?.code?.let { status -> " status=$status" } ?: ""))
                    throw failure
                }
            }
        }
    }
}

private fun hexStringToBytes(hexString: String): ByteArray {
    val cleanHex = hexString.replace("\\s+".toRegex(), "")
    val length = cleanHex.length

    // Check for even number of characters
    if (length % 2 != 0) {
        throw IllegalArgumentException("Hex string must have even number of characters")
    }

    val bytes = ByteArray(length / 2)
    for (i in 0 until length step 2) {
        val hexByte = cleanHex.substring(i, i + 2)
        bytes[i / 2] = hexByte.toInt(16).toByte()
    }
    return bytes
}
