package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest

interface TTSProvider<T : TTSProviderSetting> {
    fun supportsStreaming(providerSetting: T): Boolean = false

    fun generateSpeech(
        context: Context,
        providerSetting: T,
        request: TTSRequest
    ): Flow<AudioChunk>

    fun generateStreamingSpeech(
        context: Context,
        providerSetting: T,
        request: TTSRequest
    ): Flow<AudioChunk> = generateSpeech(context, providerSetting, request)
}
