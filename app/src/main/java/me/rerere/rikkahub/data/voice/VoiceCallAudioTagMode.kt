package me.rerere.rikkahub.data.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.isElevenLabsV3

/** Controls where voice-call emotion/audio markers are produced. */
@Serializable
enum class VoiceCallAudioTagMode {
    @SerialName("disabled")
    DISABLED,

    @SerialName("second_pass")
    SECOND_PASS,

    @SerialName("realtime_model")
    REALTIME_MODEL,
}

/**
 * Provider policy is independent from the user preference. The preference
 * remains the fallback for other providers, while the two supported marker
 * providers use their tested modes consistently.
 */
internal fun VoiceCallAudioTagMode.forVoiceCallProvider(
    provider: TTSProviderSetting?,
): VoiceCallAudioTagMode = when {
    provider?.isElevenLabsV3() == true -> VoiceCallAudioTagMode.SECOND_PASS
    provider is TTSProviderSetting.MiniMax &&
        provider.model.trim().lowercase() in setOf("speech-2.8-hd", "speech-2.8-turbo") ->
        VoiceCallAudioTagMode.DISABLED
    else -> this
}

internal val VoiceCallAudioTagMode.displayName: String
    get() = when (this) {
        VoiceCallAudioTagMode.DISABLED -> "关闭标签"
        VoiceCallAudioTagMode.SECOND_PASS -> "二次情绪标签"
        VoiceCallAudioTagMode.REALTIME_MODEL -> "模型实时标签"
    }
