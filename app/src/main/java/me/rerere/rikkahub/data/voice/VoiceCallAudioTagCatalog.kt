package me.rerere.rikkahub.data.voice

import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.isElevenLabsV3

internal const val NO_VOICE_CALL_AUDIO_TAG_ID = "NONE"

/**
 * Provider-neutral vocabulary selected by the second-pass voice director.
 *
 * The model returns stable IDs only. Provider syntax is applied by
 * [VoiceCallAudioTagFormat], so square or round delimiters never enter the
 * persisted plain reply or the model-owned text.
 */
internal enum class VoiceCallAudioTag(
    val id: String,
    val word: String,
    val isCommon: Boolean = false,
    val leadingInterjections: Set<String> = emptySet(),
) {
    LAUGHS(
        "LAUGHS",
        "laughs",
        isCommon = true,
        leadingInterjections = setOf("嘿嘿", "嘿嘿嘿", "哈哈", "哈哈哈", "嘻嘻", "嘻嘻嘻"),
    ),
    CHUCKLE("CHUCKLE", "chuckle", isCommon = true),
    COUGHS(
        "COUGHS",
        "coughs",
        leadingInterjections = setOf("咳", "咳咳"),
    ),
    CLEAR_THROAT("CLEAR_THROAT", "clear-throat"),
    GROANS("GROANS", "groans"),
    BREATH("BREATH", "breath", isCommon = true),
    PANT("PANT", "pant"),
    INHALE("INHALE", "inhale"),
    EXHALE("EXHALE", "exhale"),
    GASPS("GASPS", "gasps"),
    SNIFFS("SNIFFS", "sniffs"),
    SIGHS(
        "SIGHS",
        "sighs",
        isCommon = true,
        leadingInterjections = setOf("唉", "哎", "啊", "唉唉", "哎哎", "啊啊"),
    ),
    SNORTS("SNORTS", "snorts"),
    BURPS("BURPS", "burps"),
    LIP_SMACKING("LIP_SMACKING", "lip-smacking"),
    HUMMING("HUMMING", "humming"),
    HISSING("HISSING", "hissing"),
    EMM(
        "EMM",
        "emm",
        leadingInterjections = setOf("嗯", "嗯嗯", "嗯哼", "呃", "emm"),
    ),
    SNEEZES("SNEEZES", "sneezes");

    companion object {
        private val byId = entries.associateBy { it.id }
        private val byWord = entries.associateBy { it.word.lowercase() }

        fun fromId(id: String): VoiceCallAudioTag? = byId[id.trim().uppercase()]

        fun fromWord(word: String): VoiceCallAudioTag? = byWord[word.trim().lowercase()]
    }
}

internal enum class VoiceCallAudioTagFormat(
    val providerName: String,
    private val openingDelimiter: String,
    private val closingDelimiter: String,
    val allowsNoTag: Boolean,
    private val speechSeparator: String,
) {
    ELEVEN_LABS_V3(
        providerName = "ElevenLabs v3",
        openingDelimiter = "[",
        closingDelimiter = "]",
        allowsNoTag = false,
        speechSeparator = " ",
    ),
    MINIMAX_SPEECH_2_8(
        providerName = "MiniMax Speech 2.8",
        openingDelimiter = "(",
        closingDelimiter = ")",
        allowsNoTag = true,
        speechSeparator = "，",
    );

    fun render(word: String): String = "$openingDelimiter$word$closingDelimiter"

    fun renderForSpeech(word: String): String = render(word) + speechSeparator
}

internal fun TTSProviderSetting.voiceCallAudioTagFormatOrNull(): VoiceCallAudioTagFormat? {
    return when {
        isElevenLabsV3() -> VoiceCallAudioTagFormat.ELEVEN_LABS_V3
        this is TTSProviderSetting.MiniMax && model.trim().lowercase() in MINIMAX_SPEECH_2_8_MODELS ->
            VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8

        else -> null
    }
}

private val MINIMAX_SPEECH_2_8_MODELS = setOf(
    "speech-2.8-hd",
    "speech-2.8-turbo",
)
