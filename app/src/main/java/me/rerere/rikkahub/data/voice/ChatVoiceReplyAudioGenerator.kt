package me.rerere.rikkahub.data.voice

import androidx.core.net.toUri
import me.rerere.ai.ui.ChatVoiceAudioSegment
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.service.sanitizeVoiceCallTextForSpeech
import me.rerere.rikkahub.utils.toChatTtsText
import me.rerere.tts.controller.TextChunker
import me.rerere.tts.controller.TtsSynthesizer
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting

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
        return splitVoiceCallAudioTaggingSegments(speechText).mapNotNull { sentence ->
            val sentenceChunks = chunker.split(sentence)
            if (sentenceChunks.isEmpty()) return@mapNotNull null
            val audioSegments = sentenceChunks.map { chunk ->
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
            ChatVoiceReplyAudioGroup(
                text = sentence,
                audioSegments = audioSegments,
            )
        }
    }
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
