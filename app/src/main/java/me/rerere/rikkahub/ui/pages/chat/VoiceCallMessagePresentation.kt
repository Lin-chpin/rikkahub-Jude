package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.VoiceCallAudioSegment
import me.rerere.rikkahub.data.voice.voiceCallDisplayTextOrPlainText

internal sealed interface VoiceCallDisplayItem {
    data class Text(val text: String) : VoiceCallDisplayItem
    data class Voice(
        val text: String,
        val durationSeconds: Int,
        val audioSegment: VoiceCallAudioSegment? = null,
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

internal fun UIMessage.voiceCallDisplayItems(
    audioSegmentsOverride: List<VoiceCallAudioSegment> = emptyList(),
    currentAssistantId: String?,
    voiceReplyPending: Boolean,
    visibleTextLength: Int,
    visibleTextOverride: Int?,
): List<VoiceCallDisplayItem> {
    val audioSegments = audioSegmentsOverride.ifEmpty {
        annotations.filterIsInstance<UIMessageAnnotation.VoiceCallRecord>()
            .flatMap { it.audioSegments }
    }
    val fullText = when (role) {
        MessageRole.ASSISTANT -> voiceCallDisplayTextOrPlainText()
        else -> toText()
    }
    val isCurrentAssistant = role == MessageRole.ASSISTANT && id.toString() == currentAssistantId
    if (isCurrentAssistant && (voiceReplyPending || visibleTextOverride != null)) {
        val visibleLength = if (voiceReplyPending) {
            visibleTextLength
        } else {
            visibleTextOverride ?: fullText.length
        }
        val visibleText = fullText.take(visibleLength)
        val items = visibleText
            .splitVoiceCallDisplayItems(asVoice = true, audioSegments = audioSegments)
            .toMutableList()
        if (voiceReplyPending && (visibleTextLength <= 0 || visibleTextLength < fullText.length)) {
            items += VoiceCallDisplayItem.Loading
        }
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
    val usedAudioSegmentIndexes = mutableSetOf<Int>()

    // Call-only tag metadata is removed after hang-up, but cached audio keeps
    // the exact TTS text. Match without the tag before positional fallback.
    return displaySegments.mapIndexed { displayIndex, segment ->
        val audioSegmentIndex = audioSegments.indices.firstOrNull { audioIndex ->
            audioIndex !in usedAudioSegmentIndexes &&
                audioSegments[audioIndex].text.matchesVoiceCallDisplayText(segment.text)
        } ?: when {
            displayIndex == 0 && audioSegments.size == 1 -> 0
            audioSegments.size == displaySegments.size && displayIndex !in usedAudioSegmentIndexes -> displayIndex
            else -> null
        }
        val audioSegment = audioSegmentIndex?.let { index ->
            usedAudioSegmentIndexes += index
            audioSegments[index]
        }
        segment.text.toVoiceCallDisplayItem(asVoice, audioSegment)
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
): VoiceCallDisplayItem {
    return if (asVoice) {
        VoiceCallDisplayItem.Voice(
            text = this,
            durationSeconds = estimateVoiceCallDurationSeconds(this),
            audioSegment = audioSegment,
        )
    } else {
        VoiceCallDisplayItem.Text(this)
    }
}
