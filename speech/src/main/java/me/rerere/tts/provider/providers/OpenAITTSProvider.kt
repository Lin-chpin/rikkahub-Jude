package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderKind
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.createTTSProviderHttpException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAITTSProvider"

private fun buildRequestUrl(baseUrl: String, speechPath: String): String {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val normalizedSpeechPath = speechPath.trim()
    if (normalizedSpeechPath.isBlank()) return normalizedBaseUrl

    val path = normalizedSpeechPath.trimStart('/')
    return if (normalizedBaseUrl.endsWith("/$path")) {
        normalizedBaseUrl
    } else {
        "$normalizedBaseUrl/$path"
    }
}

class OpenAITTSProvider : TTSProvider<TTSProviderSetting.OpenAI> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.OpenAI,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestUrl = buildRequestUrl(providerSetting.baseUrl, providerSetting.speechPath)
        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)
            put("input", request.text)
            put("voice", providerSetting.voice)
            put("response_format", "mp3") // Default to MP3
        }

        Log.i(TAG, "generateSpeech: provider=openai_compatible text_chars=${request.text.length}")

        val httpRequest = Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val audioData = httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val failure = createTTSProviderHttpException(
                    TTSProviderKind.OPENAI_COMPATIBLE,
                    response.code,
                    response.body.string(),
                )
                Log.e(TAG, "TTS HTTP failure: ${failure.message}")
                throw failure
            }
            response.body.bytes()
        }

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "openai",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voice
                )
            )
        )
    }
}
