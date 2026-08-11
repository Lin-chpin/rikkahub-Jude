package me.rerere.rikkahub.data.voice

private val squareVoiceCallAnnotationRegex =
    Regex("""(?m)(?<!\w)\[[^\]\r\n]{1,80}]\s*""")

private val leadingParenthesizedVoiceCallAnnotationRegex =
    Regex("""(?m)^\s*\([^\)\r\n]{1,80}\)\s*""")

private val knownVoiceCallTagRegex = Regex(
    VoiceCallAudioTag.entries
        .flatMap { tag ->
            VoiceCallAudioTagFormat.entries.map { format ->
                Regex.escape(format.render(tag.word))
            }
        }
        .joinToString("|", prefix = "(?i)(?:", postfix = ")\\s*"),
)

/** Removes voice-performance metadata before text is sent to translation. */
internal fun String.sanitizeVoiceCallTextForTranslation(): String {
    return replace(squareVoiceCallAnnotationRegex, "")
        .replace(knownVoiceCallTagRegex, "")
        .replace(leadingParenthesizedVoiceCallAnnotationRegex, "")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}
