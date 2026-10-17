package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.VoiceCallAudioSegment
import me.rerere.rikkahub.data.voice.voiceCallDisplayTextOrPlainText
import me.rerere.rikkahub.data.voice.withoutVoiceCallRealtimeEmotionMarker

internal const val VOICE_CALL_REPLY_TRANSLATION_KEY = "voice-call-reply"

internal sealed interface VoiceCallDisplayItem {
    data class Text(val text: String) : VoiceCallDisplayItem
    data class Voice(
        val text: String,
        val durationSeconds: Int,
        val audioSegment: VoiceCallAudioSegment? = null,
        val isWholeReplyAudio: Boolean = false,
    ) : VoiceCallDisplayItem

    data object Loading : VoiceCallDisplayItem
}

internal val VoiceCallDisplayItem.translationText: String?
    get() = when (this) {
        is VoiceCallDisplayItem.Text -> text
        is VoiceCallDisplayItem.Voice -> text
        VoiceCallDisplayItem.Loading -> null
    }

internal fun VoiceCallDisplayItem.translationKey(index: Int): String? {
    if (translationText == null) return null
    return "voice-call-bubble:$index"
}

internal fun UIMessage.voiceCallTranslationForDisplay(
    index: Int,
    itemCount: Int,
    loadingText: String,
): String? {
    val fullReplyTranslation = voiceCallTranslations[VOICE_CALL_REPLY_TRANSLATION_KEY]
        ?: return voiceCallTranslations["voice-call-bubble:$index"]
            ?: translation.takeIf { itemCount == 1 }
    if (fullReplyTranslation == loadingText) return fullReplyTranslation.takeIf { index == 0 }

    val translatedSegments = fullReplyTranslation.voiceCallDisplaySegments().map { it.text }
    if (translatedSegments.isEmpty() || itemCount <= 0) return null
    fun boundary(position: Int) =
        (position * translatedSegments.size + itemCount - 1) / itemCount
    val start = boundary(index)
    val end = boundary(index + 1)
    return translatedSegments.subList(start, end).joinToString("\n").takeIf { it.isNotBlank() }
}

internal fun UIMessage.voiceCallDisplayItems(
    audioSegmentsOverride: List<VoiceCallAudioSegment> = emptyList(),
    currentAssistantId: String?,
    textGenerationPending: Boolean,
    showAudioTags: Boolean = false,
): List<VoiceCallDisplayItem> {
    val audioSegments = audioSegmentsOverride.ifEmpty {
        annotations.filterIsInstance<UIMessageAnnotation.VoiceCallRecord>()
            .flatMap { it.audioSegments }
    }
    val fullText = when (role) {
        MessageRole.ASSISTANT -> voiceCallDisplayTextOrPlainText().withoutVoiceCallRealtimeEmotionMarker()
        else -> toText()
    }
    val isCurrentAssistant = role == MessageRole.ASSISTANT && id.toString() == currentAssistantId
    if (isCurrentAssistant && textGenerationPending) {
        // Generated text owns bubble visibility; missing alignment or stalled audio must not hide sentences.
        val items = fullText
            .splitVoiceCallDisplayItems(asVoice = true, audioSegments = audioSegments)
            .toMutableList()
        items += VoiceCallDisplayItem.Loading
        return items
    }

    if (role != MessageRole.ASSISTANT || !isCurrentAssistant) {
        return when (role) {
            MessageRole.ASSISTANT ->
                fullText.splitVoiceCallDisplayItems(asVoice = true, audioSegments = audioSegments)

            else -> listOfNotNull(fullText.takeIf { it.isNotBlank() }?.let(VoiceCallDisplayItem::Text))
        }
    }
    return fullText.splitVoiceCallDisplayItems(asVoice = true, audioSegments = audioSegments)
}

private fun String.splitVoiceCallDisplayItems(
    asVoice: Boolean,
    audioSegments: List<VoiceCallAudioSegment> = emptyList(),
): List<VoiceCallDisplayItem> {
    val displaySegments = voiceCallDisplaySegments()
    val hasWholeReplyAudio = audioSegments.size == 1 && displaySegments.size > 1 &&
        audioSegments.single().startPositionMs == null
    val usedAudioSegmentIndexes = mutableSetOf<Int>()

    // Call-only tag metadata is removed after hang-up, but cached audio keeps
    // the exact TTS text. Match without the tag before positional fallback.
    // A timestamp-free legacy recording stays attached once to the first
    // sentence bubble; it must never collapse the reply into one bubble.
    return displaySegments.mapIndexed { displayIndex, segment ->
        val audioSegmentIndex = if (hasWholeReplyAudio) {
            // Keep live bubbles stable when capture finishes; attach whole-reply replay only once.
            0.takeIf { displayIndex == 0 }
        } else if (audioSegments.size == 1) {
            0.takeIf { audioSegments.single().text.matchesVoiceCallDisplayText(segment.text) }
        } else {
            audioSegments.indices.firstOrNull { audioIndex ->
                audioIndex !in usedAudioSegmentIndexes &&
                    audioSegments[audioIndex].text.matchesVoiceCallDisplayText(segment.text)
            } ?: when {
            audioSegments.size == displaySegments.size && displayIndex !in usedAudioSegmentIndexes -> displayIndex
            else -> null
            }
        }
        val audioSegment = audioSegmentIndex?.let { index ->
            if (audioSegments.size != 1) {
                usedAudioSegmentIndexes += index
            }
            audioSegments[index]
        }
        segment.text.toVoiceCallDisplayItem(asVoice, audioSegment, hasWholeReplyAudio && audioSegment != null)
    }
}

private val voiceCallAudioAnnotationPrefixRegex =
    Regex("""^\s*(?:\[[^\]\r\n]{1,80}]|\([^\)\r\n]{1,80}\))\s*""")

private fun String.matchesVoiceCallDisplayText(displayText: String): Boolean {
    return this == displayText ||
        replace(voiceCallAudioAnnotationPrefixRegex, "").trim() ==
        displayText.replace(voiceCallAudioAnnotationPrefixRegex, "").trim()
}

private fun String.toVoiceCallDisplayItem(
    asVoice: Boolean,
    audioSegment: VoiceCallAudioSegment? = null,
    isWholeReplyAudio: Boolean = false,
): VoiceCallDisplayItem {
    return if (asVoice) {
        VoiceCallDisplayItem.Voice(
            text = this,
            durationSeconds = audioSegment?.let { audio ->
                val start = audio.startPositionMs
                val end = audio.endPositionMs
                if (start != null && end != null && end > start) ((end - start + 999) / 1_000).toInt() else null
            } ?: estimateVoiceCallDurationSeconds(if (isWholeReplyAudio) audioSegment?.text ?: this else this),
            audioSegment = audioSegment,
            isWholeReplyAudio = isWholeReplyAudio,
        )
    } else {
        VoiceCallDisplayItem.Text(this)
    }
}
