package me.rerere.tts.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.provider.TTSProviderSetting

/** Opens the existing dialogue stream without sending any text or starting playback. */
internal class RealtimeDialoguePreconnection(
    scope: CoroutineScope,
    private val provider: TTSProviderSetting.ElevenLabs,
    generateAudio: (Flow<String>) -> Flow<AudioChunk>,
    onFailure: (Throwable) -> Unit = {},
) {
    private val input = Channel<String>(Channel.BUFFERED)
    private val audio = Channel<AudioChunk>(Channel.BUFFERED)
    private val connectionJob = scope.launch(Dispatchers.IO) {
        try {
            generateAudio(input.receiveAsFlow()).collect { audio.send(it) }
            audio.close()
        } catch (error: CancellationException) {
            audio.cancel(error)
            throw error
        } catch (error: Exception) {
            audio.close(error)
            onFailure(error)
        } finally {
            input.cancel()
        }
    }

    fun matches(provider: TTSProviderSetting.ElevenLabs): Boolean =
        this.provider == provider && connectionJob.isActive

    fun audioFor(text: Flow<String>): Flow<AudioChunk> = flow {
        try {
            coroutineScope {
                launch {
                    text.collect { input.send(it) }
                    input.close()
                }
                emitAll(audio.receiveAsFlow())
            }
        } finally {
            close()
        }
    }

    fun close() {
        connectionJob.cancel()
        input.cancel()
        audio.cancel()
    }
}
