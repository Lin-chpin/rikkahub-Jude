package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.CachedAudioSource
import me.rerere.tts.model.TTSResponse
import org.koin.compose.koinInject
import java.io.File

private const val CHAT_TTS_FILE_NAME = "chat-tts"

@Composable
fun rememberChatTtsPlayback(): ChatTtsPlayback {
    val tts = me.rerere.rikkahub.ui.context.LocalTTSState.current
    val filesManager = koinInject<FilesManager>()
    val scope = rememberCoroutineScope()
    return remember(tts, filesManager, scope) {
        ChatTtsPlayback(tts, filesManager, scope)
    }
}

class ChatTtsPlayback(
    private val tts: CustomTtsState,
    private val filesManager: FilesManager,
    private val scope: CoroutineScope,
) {
    private val saveMutex = Mutex()

    fun speak(
        message: UIMessage,
        text: String,
        onUpdateMessage: (messageId: kotlin.uuid.Uuid, transform: (UIMessage) -> UIMessage) -> Unit,
        flushCalled: Boolean = true,
    ) {
        val requestText = text.trim()
        if (requestText.isBlank()) return

        val cached = message.annotations
            .filterIsInstance<UIMessageAnnotation.TtsAudio>()
            .filter { it.requestText == requestText && isCachedFileAvailable(it.audioUri) }
            .sortedBy { it.chunkIndex }
        val expectedChunks = cached.firstOrNull()?.totalChunks
        if (cached.isNotEmpty() && expectedChunks == cached.size && cached.map { it.chunkIndex } == (0 until cached.size).toList()) {
            tts.playCachedAudios(
                cached.map { audio ->
                    CachedAudioSource(
                        audioUri = audio.audioUri,
                        format = AudioFormat.valueOf(audio.format),
                        sampleRate = audio.sampleRate,
                    )
                },
                flushCalled = flushCalled,
            )
            return
        }

        // A changed spoken-text setting (for example ttsEnglishOnly) gets a new requestText.
        // Remove an incomplete cache for this exact request before synthesizing again.
        onUpdateMessage(message.id) { current ->
            current.copy(
                annotations = current.annotations.filterNot {
                    it is UIMessageAnnotation.TtsAudio && it.requestText == requestText
                }
            )
        }

        tts.speak(
            text = requestText,
            flushCalled = flushCalled,
            chunked = true,
            onAudioReadyWithChunk = { chunkText, chunkIndex, totalChunks, response ->
                saveAudio(
                    messageId = message.id,
                    requestText = requestText,
                    chunkText = chunkText,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    response = response,
                    onUpdateMessage = onUpdateMessage,
                )
            },
        )
    }

    private fun saveAudio(
        messageId: kotlin.uuid.Uuid,
        requestText: String,
        chunkText: String,
        chunkIndex: Int,
        totalChunks: Int,
        response: TTSResponse,
        onUpdateMessage: (messageId: kotlin.uuid.Uuid, transform: (UIMessage) -> UIMessage) -> Unit,
    ) {
        scope.launch {
            saveMutex.withLock {
                val managedFile = filesManager.saveUploadFromBytes(
                    bytes = response.audioData,
                    displayName = CHAT_TTS_FILE_NAME,
                    mimeType = response.format.toMimeType(),
                )
                val audioUri = filesManager.getFile(managedFile).toUri().toString()
                onUpdateMessage(messageId) { current ->
                    val annotations = current.annotations
                        .filterNot {
                            it is UIMessageAnnotation.TtsAudio &&
                                it.requestText == requestText &&
                                it.chunkIndex == chunkIndex
                        }
                        .plus(
                            UIMessageAnnotation.TtsAudio(
                                requestText = requestText,
                                chunkText = chunkText,
                                audioUri = audioUri,
                                format = response.format.name,
                                sampleRate = response.sampleRate,
                                chunkIndex = chunkIndex,
                                totalChunks = totalChunks,
                            )
                        )
                    current.copy(annotations = annotations)
                }
            }
        }
    }

    private fun isCachedFileAvailable(audioUri: String): Boolean {
        val uri = audioUri.toUri()
        return when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let(::File)?.isFile == true
            else -> true
        }
    }
}

private fun AudioFormat.toMimeType(): String = when (this) {
    AudioFormat.MP3 -> "audio/mpeg"
    AudioFormat.WAV -> "audio/wav"
    AudioFormat.OGG -> "audio/ogg"
    AudioFormat.AAC -> "audio/aac"
    AudioFormat.OPUS -> "audio/opus"
    AudioFormat.PCM -> "audio/pcm"
}
