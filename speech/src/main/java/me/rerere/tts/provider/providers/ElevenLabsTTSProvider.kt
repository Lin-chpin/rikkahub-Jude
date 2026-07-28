package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ElevenLabsTTSProvider"
private const val DEFAULT_OUTPUT_FORMAT = "mp3_44100_128"

private fun normalizeElevenLabsModelId(model: String): String {
    return when {
        model.trim().equals("eleven_multilingual_v3", ignoreCase = true) -> "eleven_v3"
        else -> model.trim()
    }
}

private fun buildElevenLabsRequestUrl(baseUrl: String, voiceId: String, streaming: Boolean): String {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val versionedBaseUrl = if (normalizedBaseUrl.endsWith("/v1")) {
        normalizedBaseUrl
    } else {
        "$normalizedBaseUrl/v1"
    }
    val streamSuffix = if (streaming) "/stream" else ""
    return "$versionedBaseUrl/text-to-speech/${voiceId.trim()}$streamSuffix?output_format=$DEFAULT_OUTPUT_FORMAT"
}

class ElevenLabsTTSProvider : TTSProvider<TTSProviderSetting.ElevenLabs> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun supportsStreaming(providerSetting: TTSProviderSetting.ElevenLabs): Boolean {
        return normalizeElevenLabsModelId(providerSetting.model) in setOf(
            "eleven_multilingual_v2",
            "eleven_v3",
        )
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.ElevenLabs,
        request: TTSRequest
    ): Flow<AudioChunk> = generateSpeechFlow(providerSetting, request, streaming = false)

    override fun generateStreamingSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.ElevenLabs,
        request: TTSRequest,
    ): Flow<AudioChunk> = generateSpeechFlow(providerSetting, request, streaming = true)

    private fun generateSpeechFlow(
        providerSetting: TTSProviderSetting.ElevenLabs,
        request: TTSRequest,
        streaming: Boolean,
    ): Flow<AudioChunk> = flow {
        val apiKey = providerSetting.apiKey.trim()
        val voiceId = providerSetting.voiceId.trim()
        val baseUrl = providerSetting.baseUrl.trim()
        val modelId = normalizeElevenLabsModelId(providerSetting.model)
        require(apiKey.isNotEmpty()) { "ElevenLabs API key is required" }
        require(voiceId.isNotEmpty()) { "ElevenLabs voice ID is required" }
        require(baseUrl.isNotEmpty()) { "ElevenLabs base URL is required" }

        val requestUrl = buildElevenLabsRequestUrl(baseUrl, voiceId, streaming)
        val requestBody = JSONObject().apply {
            put("text", request.text)
            if (modelId.isNotBlank()) {
                put("model_id", modelId)
            }
            put(
                "voice_settings",
                JSONObject().apply {
                    put(
                        "stability",
                        providerSetting.stability.coerceIn(
                            TTSProviderSetting.ElevenLabs.MIN_STABILITY,
                            TTSProviderSetting.ElevenLabs.MAX_STABILITY,
                        ),
                    )
                    put(
                        "similarity_boost",
                        providerSetting.similarityBoost.coerceIn(
                            TTSProviderSetting.ElevenLabs.MIN_SIMILARITY_BOOST,
                            TTSProviderSetting.ElevenLabs.MAX_SIMILARITY_BOOST,
                        ),
                    )
                    put("use_speaker_boost", providerSetting.useSpeakerBoost)
                    put(
                        "style",
                        providerSetting.style.coerceIn(
                            TTSProviderSetting.ElevenLabs.MIN_STYLE,
                            TTSProviderSetting.ElevenLabs.MAX_STYLE,
                        ),
                    )
                    put(
                        "speed",
                        providerSetting.speed.coerceIn(
                            TTSProviderSetting.ElevenLabs.MIN_SPEED,
                            TTSProviderSetting.ElevenLabs.MAX_SPEED,
                        ),
                    )
                },
            )
        }

        Log.i(
            TAG,
            "generateSpeech: streaming=$streaming, url=$requestUrl, model=$modelId, voiceId=$voiceId"
        )

        val httpRequest = Request.Builder()
            .url(requestUrl)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                Log.e(TAG, "generateSpeech: ${response.code} ${response.message}, body=$errorBody")
                throw Exception("ElevenLabs TTS request failed: ${response.code} ${response.message}; body=$errorBody")
            }

            val responseBody = response.body ?: error("ElevenLabs TTS returned an empty response")
            val metadata = mapOf(
                "provider" to "elevenlabs",
                "model" to modelId,
                "voice_id" to voiceId,
            )
            if (streaming) {
                responseBody.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (currentCoroutineContext().isActive) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        if (bytesRead == 0) continue
                        emit(
                            AudioChunk(
                                data = buffer.copyOf(bytesRead),
                                format = AudioFormat.MP3,
                                metadata = metadata,
                            )
                        )
                    }
                }
            } else {
                emit(
                    AudioChunk(
                        data = responseBody.bytes(),
                        format = AudioFormat.MP3,
                        isLast = true,
                        metadata = metadata,
                    )
                )
            }
        }
    }
}
