package me.rerere.rikkahub.data.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.isElevenLabsV4Family
import me.rerere.tts.provider.supportsElevenLabsAudioTags

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

/** ElevenLabs v3/v4 tags come inline from the primary model; other providers use the saved preference. */
internal fun VoiceCallAudioTagMode.forVoiceCallProvider(
    provider: TTSProviderSetting?,
): VoiceCallAudioTagMode = when {
    provider?.isElevenLabsV4Family() == true || provider?.supportsElevenLabsAudioTags() == true ->
        VoiceCallAudioTagMode.REALTIME_MODEL
    provider is TTSProviderSetting.MiniMax &&
        provider.model.trim().lowercase() in setOf("speech-2.8-hd", "speech-2.8-turbo") ->
        VoiceCallAudioTagMode.DISABLED
    else -> this
}
