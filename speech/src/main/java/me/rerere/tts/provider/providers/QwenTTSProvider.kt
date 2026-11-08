package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderKind
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.TTSResponseParsingException
import me.rerere.tts.provider.createTTSProviderHttpException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "QwenTTSProvider"

internal fun parseQwenSseAudioData(data: String): Pair<ByteArray, Boolean>? {
    try {
        val root = Json.parseToJsonElement(data).jsonObject
        val output = root["output"]?.jsonObject ?: error("Missing output object")
        val audio = output["audio"]?.jsonObject ?: error("Missing audio object")
        val encodedAudio = (audio["data"] as? JsonPrimitive)?.contentOrNull ?: error("Missing audio data")
        if (encodedAudio.isBlank()) return null
        val audioBytes = Base64.getDecoder().decode(encodedAudio.filterNot(Char::isWhitespace))
        if (audioBytes.isEmpty()) error("Empty audio data")
        val finishReason = (output["finish_reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return audioBytes to (finishReason == "stop")
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        throw TTSResponseParsingException(TTSProviderKind.QWEN, error)
    }
}

class QwenTTSProvider : TTSProvider<TTSProviderSetting.Qwen> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Qwen,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)
            put("input", JSONObject().apply {
                put("text", request.text)
                put("voice", providerSetting.voice)
                put("language_type", providerSetting.languageType)
            })
        }

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/services/aigc/multimodal-generation/generation")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-DashScope-SSE", "enable")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val failure = createTTSProviderHttpException(
                TTSProviderKind.QWEN,
                response.code,
                response.body.string(),
            )
            Log.e(TAG, "TTS HTTP failure: ${failure.message}")
            response.close()
            throw failure
        }

        val reader = response.body.byteStream().bufferedReader()

        try {
            var currentData = StringBuilder()
            var hasAudio = false

            reader.lineSequence().forEach { line ->
                when {
                    line.startsWith("data:") -> {
                        currentData.append(line.removePrefix("data:"))
                    }

                    line.isEmpty() && currentData.isNotEmpty() -> {
                        val result = parseQwenSseAudioData(currentData.toString())
                        if (result != null) {
                            val (audioData, isLast) = result
                            hasAudio = hasAudio || audioData.isNotEmpty()
                            emit(
                                AudioChunk(
                                    data = audioData,
                                    format = AudioFormat.PCM,
                                    sampleRate = 24000,
                                    isLast = isLast,
                                    metadata = mapOf(
                                        "provider" to "qwen",
                                        "model" to providerSetting.model,
                                        "voice" to providerSetting.voice,
                                        "sampleRate" to "24000",
                                        "channels" to "1",
                                        "bitDepth" to "16"
                                    )
                                )
                            )
                        }
                        currentData = StringBuilder()
                    }
                }
            }
            if (!hasAudio) throw TTSResponseParsingException(TTSProviderKind.QWEN)
        } finally {
            reader.close()
        }
    }

}
