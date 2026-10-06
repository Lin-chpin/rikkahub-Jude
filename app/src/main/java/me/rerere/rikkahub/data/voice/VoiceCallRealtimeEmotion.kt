package me.rerere.rikkahub.data.voice

import me.rerere.tts.provider.TTSProviderSetting

private val voiceCallEmotionMarkerRegex = Regex(
    pattern = "(?i)_{1,2}VOICE_CALL_EMOTION_{1,2}\\s*:\\s*(?:<\\s*([a-z]+)\\s*>|([a-z]+))",
)

private val voiceCallRealtimeEmotionCatalog = TTSProviderSetting.MiniMax.GLOBAL_EMOTION_OPTIONS
    .filter { it != "whipser" }
    .map(String::lowercase)
    .toSet()

internal fun String.voiceCallRealtimeEmotionOrNull(): String? {
    val match = voiceCallEmotionMarkerRegex.find(this) ?: return null
    val emotion = match.groupValues.getOrNull(1).orEmpty()
        .ifEmpty { match.groupValues.getOrNull(2).orEmpty() }
        .lowercase()
    return emotion.takeIf { it in voiceCallRealtimeEmotionCatalog }
}

internal fun String.withoutVoiceCallRealtimeEmotionMarker(): String =
    replace(voiceCallEmotionMarkerRegex, "").trimStart()
