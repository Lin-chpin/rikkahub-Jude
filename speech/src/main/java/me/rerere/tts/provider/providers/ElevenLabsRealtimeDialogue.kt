package me.rerere.tts.provider.providers

import android.util.Base64
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val ELEVENLABS_TURBO_MODEL = "eleven_v4_turbo"
private const val DIALOGUE_OUTPUT_FORMAT = "mp3_44100_128"
private const val TAG = "ElevenLabsDialogue"

private val dialogueWebSocketClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .build()

internal fun generateElevenLabsRealtimeDialogue(
    provider: TTSProviderSetting.ElevenLabs,
    text: Flow<String>,
): Flow<AudioChunk> = callbackFlow {
    val output = this
    val apiKey = provider.apiKey.trim()
    val voiceId = provider.voiceId.trim()
    require(apiKey.isNotEmpty()) { "ElevenLabs API key is required" }
    require(voiceId.isNotEmpty()) { "ElevenLabs voice ID is required" }
    require(provider.model.trim().equals(ELEVENLABS_TURBO_MODEL, ignoreCase = true)) {
        "Realtime dialogue requires ElevenLabs v4 Turbo"
    }

    val socketReady = CompletableDeferred<WebSocket>()
    val inputFinished = AtomicBoolean(false)
    val finalReceived = AtomicBoolean(false)
    val keepAliveLock = Any()
    val startedAt = SystemClock.elapsedRealtime()
    val sessionId = SystemClock.elapsedRealtimeNanos().toString(16)
    val firstAudioReceived = AtomicBoolean(false)
    fun logTiming(event: String) {
        Log.i(TAG, "session=$sessionId +${SystemClock.elapsedRealtime() - startedAt}ms $event")
    }
    val request = Request.Builder()
        .url(provider.baseUrl.toElevenLabsDialogueUrl(provider.languageCode))
        .addHeader("xi-api-key", apiKey)
        .build()

    logTiming("connecting")
    val socket = dialogueWebSocketClient.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val voiceSettings = JSONObject()
                .put(
                    "stability",
                    provider.stability.coerceIn(
                        TTSProviderSetting.ElevenLabs.MIN_STABILITY,
                        TTSProviderSetting.ElevenLabs.MAX_STABILITY,
                    ),
                )
                .put(
                    "similarity_boost",
                    provider.similarityBoost.coerceIn(
                        TTSProviderSetting.ElevenLabs.MIN_SIMILARITY_BOOST,
                        TTSProviderSetting.ElevenLabs.MAX_SIMILARITY_BOOST,
                    ),
                )
            val registration = JSONObject()
                .put("voices", JSONArray().put(voiceId))
                .put("voice_settings", voiceSettings)
            if (!webSocket.send(registration.toString())) {
                val error = IOException("ElevenLabs dialogue WebSocket rejected voice registration")
                socketReady.completeExceptionally(error)
                output.close(error)
                webSocket.cancel()
            } else {
                logTiming("connected; voice registered")
                socketReady.complete(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSONObject(text) }.getOrElse {
                output.close(IOException("Invalid ElevenLabs dialogue response", it))
                return
            }
            if (message.has("error") && !message.isNull("error")) {
                output.close(IOException("ElevenLabs dialogue error: ${message.opt("error")}"))
                return
            }
            message.optString("audio")
                .takeIf(String::isNotEmpty)
                ?.let { encoded ->
                    val audio = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                        .getOrElse {
                            output.close(IOException("Invalid ElevenLabs dialogue audio", it))
                            return
                        }
                    if (audio.isNotEmpty() && firstAudioReceived.compareAndSet(false, true)) {
                        logTiming("first audio received: bytes=${audio.size}")
                    }
                    val sendResult = output.trySend(
                        AudioChunk(
                            data = audio,
                            format = AudioFormat.MP3,
                            sampleRate = 44_100,
                            metadata = mapOf(
                                "provider" to "elevenlabs",
                                "model" to ELEVENLABS_TURBO_MODEL,
                                "voice_id" to voiceId,
                            ),
                        ),
                    )
                    if (sendResult.isFailure) output.close(sendResult.exceptionOrNull())
                }
            if (message.optBoolean("is_final")) {
                logTiming("final audio received")
                finalReceived.set(true)
                output.close()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = response?.let { IOException("ElevenLabs dialogue WebSocket failed: ${it.code} ${it.message}", t) } ?: t
            socketReady.completeExceptionally(error)
            output.close(error)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (finalReceived.get()) output.close()
            else output.close(IOException("ElevenLabs dialogue WebSocket closed before final audio: $code $reason"))
        }
    })

    val sender = launch {
        try {
            val readySocket = socketReady.await()
            val pendingText = StringBuilder()
            var sentCharacters = 0
            fun sendText(value: String) {
                if (!readySocket.send(dialogueInput(value, voiceId))) {
                    throw IOException("ElevenLabs dialogue WebSocket stopped accepting text")
                }
                if (sentCharacters == 0) logTiming("first text queued: chars=${value.length}")
                sentCharacters += value.length
            }
            text.collect { delta ->
                pendingText.append(delta)
                val end = elevenLabsDialogueInputReadyEnd(pendingText.toString())
                if (end > 0) {
                    sendText(pendingText.substring(0, end))
                    pendingText.delete(0, end)
                }
            }
            if (pendingText.isNotEmpty()) sendText(pendingText.toString())
            logTiming("text input completed: chars=$sentCharacters")
            val closeSent = synchronized(keepAliveLock) {
                inputFinished.set(true)
                readySocket.send(JSONObject().put("close_socket", true).toString())
            }
            if (!closeSent) {
                throw IOException("ElevenLabs dialogue WebSocket could not be flushed")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            output.close(error)
        }
    }

    val keepAlive = launch {
        try {
            val readySocket = socketReady.await()
            while (isActive && !inputFinished.get()) {
                delay(15_000)
                val keepAliveSent = synchronized(keepAliveLock) {
                    if (inputFinished.get()) null
                    else readySocket.send(JSONObject().put("keep_alive", true).toString())
                }
                if (keepAliveSent == null) break
                if (!keepAliveSent) {
                    throw IOException("ElevenLabs dialogue WebSocket keep-alive failed")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            output.close(error)
        }
    }

    awaitClose {
        sender.cancel()
        keepAlive.cancel()
        socket.cancel()
    }
}

// Leave generation timing to the server; only buffer incomplete tags or Unicode characters.
internal fun elevenLabsDialogueInputReadyEnd(text: String): Int {
    var end = text.length
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    val opening = text.lastIndexOf('[', end - 1)
    val closing = text.lastIndexOf(']', end - 1)
    return if (opening > closing) opening else end
}

private fun dialogueInput(text: String, voiceId: String): String = JSONObject()
    .put(
        "inputs",
        JSONArray().put(
            JSONObject()
                .put("text", text)
                .put("voice_id", voiceId)
                .put("new_turn", false),
        ),
    )
    .toString()

private fun String.toElevenLabsDialogueUrl(languageCode: String?): String {
    val base = trim().toHttpUrlOrNull() ?: error("Invalid ElevenLabs base URL")
    val path = base.encodedPath.trimEnd('/')
    val versionedPath = if (path.endsWith("/v1")) path else "$path/v1"
    val endpoint = base.newBuilder()
        .scheme("https")
        .encodedPath("$versionedPath/text-to-dialogue/stream-input")
        .removeAllQueryParameters("model_id")
        .removeAllQueryParameters("output_format")
        .removeAllQueryParameters("language_code")
        .addQueryParameter("model_id", ELEVENLABS_TURBO_MODEL)
        .addQueryParameter("output_format", DIALOGUE_OUTPUT_FORMAT)
        .apply {
            languageCode?.trim()?.takeIf(String::isNotEmpty)?.let {
                addQueryParameter("language_code", it)
            }
        }
        .build()
    return endpoint.toString().replaceFirst("https://", "wss://")
}
