package me.rerere.rikkahub.data.voice

import me.rerere.tts.provider.TTSProviderSetting

private val voiceCallEmotionMarkerRegex = Regex(
    pattern = "(?i)_{1,2}VOICE_CALL_EMOTION_{1,2}\\s*:\\s*([a-z]+)",
)

private val voiceCallRealtimeEmotionCatalog = TTSProviderSetting.MiniMax.GLOBAL_EMOTION_OPTIONS
    .filter { it != "whipser" }
    .map(String::lowercase)
    .toSet()

internal fun String.voiceCallRealtimeEmotionOrNull(): String? =
    voiceCallEmotionMarkerRegex.find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()
        ?.takeIf { it in voiceCallRealtimeEmotionCatalog }

internal fun String.withoutVoiceCallRealtimeEmotionMarker(): String =
    replace(voiceCallEmotionMarkerRegex, "").trimStart()
