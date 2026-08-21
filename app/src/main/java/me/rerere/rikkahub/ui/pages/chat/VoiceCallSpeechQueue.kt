package me.rerere.rikkahub.ui.pages.chat

/**
 * Keeps a single voice-call reply attached to one TTS queue session.
 *
 * The first segment replaces an older reply. Later segments must append to
 * the same controller queue; replacing them would cancel the previous
 * segment's worker and replay state.
 */
internal class VoiceCallSpeechQueue {
    private var hasSubmittedSegment = false

    fun flushForNextSegment(): Boolean {
        if (hasSubmittedSegment) return false
        hasSubmittedSegment = true
        return true
    }
}
