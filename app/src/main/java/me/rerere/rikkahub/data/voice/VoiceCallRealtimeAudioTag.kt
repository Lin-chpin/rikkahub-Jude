package me.rerere.rikkahub.data.voice

private val voiceCallRealtimeAudioTagRegex = Regex("""\[[^\]\r\n]{1,80}]""")

internal fun String.withoutIncompleteVoiceCallAudioTag(): String {
    val opening = lastIndexOf('[')
    return if (opening > lastIndexOf(']')) substring(0, opening) else this
}

internal fun String.withoutFlexibleVoiceCallAudioTags(): String =
    replace(voiceCallRealtimeAudioTagRegex, "")

internal fun String.flexibleVoiceCallAudioTags(): List<String> =
    voiceCallRealtimeAudioTagRegex.findAll(this).map { it.value }.toList()
