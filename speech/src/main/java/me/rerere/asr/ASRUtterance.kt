package me.rerere.asr

/** One provider utterance, distinct from the accumulated dictation transcript. */
data class ASRUtterance(
    val id: String,
    val text: String,
    val isFinal: Boolean,
    val isSpeechStarted: Boolean = false,
)
