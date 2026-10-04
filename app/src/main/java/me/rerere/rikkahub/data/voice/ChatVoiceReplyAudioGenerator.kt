package me.rerere.rikkahub.data.voice

import androidx.core.net.toUri
import me.rerere.ai.ui.ChatVoiceAudioSegment
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.service.sanitizeVoiceCallTextForSpeech
import me.rerere.rikkahub.utils.toChatTtsText
import me.rerere.tts.controller.TextChunker
import me.rerere.tts.controller.TtsChunk
import me.rerere.tts.controller.TtsSynthesizer
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.isElevenLabsV4Family

class ChatVoiceReplyAudioGenerator(
    ttsManager: TTSManager,
    private val filesManager: FilesManager,
) {
    private val chunker = TextChunker(maxChunkLength = 160)
    private val synthesizer = TtsSynthesizer(ttsManager)

    suspend fun generate(
        text: String,
        provider: TTSProviderSetting,
        englishOnly: Boolean,
    ): List<ChatVoiceReplyAudioGroup> {
        val speechText = text
            .sanitizeVoiceCallTextForSpeech()
            .toChatTtsText(
                ttsOnlyReadQuoted = false,
                ttsEnglishOnly = englishOnly,
            )
        val chunks = if (provider is TTSProviderSetting.MiniMax) {
            miniMaxChatVoiceReplyChunks(speechText)
        } else if (provider.isElevenLabsV4Family()) {
            elevenLabsV4ChatVoiceReplyChunks(speechText)
        } else {
            splitVoiceCallAudioTaggingSegments(speechText).flatMap(chunker::split)
        }
        if (chunks.isEmpty()) return emptyList()

        val audioSegments = chunks.map { chunk ->
            val response = synthesizer.synthesize(provider, chunk)
            check(response.audioData.isNotEmpty()) { "TTS response returned empty audio" }
            val managedFile = filesManager.saveUploadFromBytes(
                bytes = response.audioData,
                displayName = "chat-voice-message",
                mimeType = response.format.toMimeType(),
            )
            ChatVoiceAudioSegment(
                text = chunk.text,
                audioUri = filesManager.getFile(managedFile).toUri().toString(),
                format = response.format.name,
                sampleRate = response.sampleRate,
            )
        }
        return listOf(ChatVoiceReplyAudioGroup(speechText, audioSegments))
    }
}

internal fun elevenLabsV4ChatVoiceReplyChunks(text: String): List<TtsChunk> {
    if (text.isBlank()) return emptyList()
    require(text.length <= 10_000) { "invalid request: ElevenLabs v4 models support at most 10000 characters" }
    return listOf(TtsChunk(index = 0, text = text))
}

private const val MINIMAX_SINGLE_REQUEST_MAX_CHARS = 9_999

internal fun miniMaxChatVoiceReplyChunks(text: String): List<TtsChunk> {
    if (text.isBlank()) return emptyList()
    val chunks = mutableListOf<TtsChunk>()
    var start = 0
    while (start < text.length) {
        val limit = minOf(start + MINIMAX_SINGLE_REQUEST_MAX_CHARS, text.length)
        val boundary = if (limit < text.length) {
            text.lastIndexOfAny(charArrayOf('\n', '。', '！', '？', '.', '!', '?', '；', ';', ' ', '\t'), limit - 1)
                .takeIf { it >= start + MINIMAX_SINGLE_REQUEST_MAX_CHARS / 2 }
                ?.plus(1)
        } else null
        val end = (boundary ?: limit).let { candidate ->
            if (candidate == limit && limit < text.length &&
                Character.isHighSurrogate(text[limit - 1]) && Character.isLowSurrogate(text[limit])
            ) limit - 1 else candidate
        }
        chunks += TtsChunk(index = chunks.size, text = text.substring(start, end))
        start = end
    }
    return chunks
}

data class ChatVoiceReplyAudioGroup(
    val text: String,
    val audioSegments: List<ChatVoiceAudioSegment>,
)

private fun AudioFormat.toMimeType(): String = when (this) {
    AudioFormat.MP3 -> "audio/mpeg"
    AudioFormat.WAV -> "audio/wav"
    AudioFormat.OGG -> "audio/ogg"
    AudioFormat.AAC -> "audio/aac"
    AudioFormat.OPUS -> "audio/opus"
    AudioFormat.PCM -> "audio/pcm"
}
