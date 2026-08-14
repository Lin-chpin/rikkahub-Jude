package me.rerere.rikkahub.data.voice

private val voiceCallRealtimeAudioTagRegex = Regex("""\[[^\]\\r\\n]{1,80}]""")

/** Keeps inline realtime speech tags inside the same finite catalog as second-pass tagging. */
internal fun String.withOnlyKnownVoiceCallAudioTags(): String {
    return replace(voiceCallRealtimeAudioTagRegex) { match ->
        val tag = VoiceCallAudioTag.fromWord(match.value.removePrefix("[").removeSuffix("]"))
        tag?.let { "[${it.word}]" }.orEmpty()
    }
}
