package me.rerere.rikkahub.ui.pages.chat

/**
 * A sentence may be visible before its tagged speech projection is ready.
 * Keep the playback cursor on that sentence while generation is active so a
 * later queue update can fill it instead of losing the index permanently.
 */
internal fun VoiceCallSpeechSegment.waitsForSpeechProjection(isGenerating: Boolean): Boolean {
    return isGenerating && text.isBlank()
}
