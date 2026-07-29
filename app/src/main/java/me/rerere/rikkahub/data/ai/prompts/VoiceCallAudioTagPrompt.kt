package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.voice.NO_VOICE_CALL_AUDIO_TAG_ID
import me.rerere.rikkahub.data.voice.VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME
import me.rerere.rikkahub.data.voice.VoiceCallAudioTag
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagFormat

/**
 * Builds the provider-neutral second-pass direction prompt.
 *
 * The selected IDs come from the shared catalog. Provider delimiters are added
 * only after validation, outside the model response.
 */
internal fun buildVoiceCallAudioTagPrompt(format: VoiceCallAudioTagFormat): String {
    val allowedTagIds = VoiceCallAudioTag.entries.joinToString(", ") { it.id }
    val commonTagIds = VoiceCallAudioTag.entries
        .filter(VoiceCallAudioTag::isCommon)
        .joinToString(", ") { it.id }
    val noTagRule = if (format.allowsNoTag) {
        """
        - Use $NO_VOICE_CALL_AUDIO_TAG_ID when a segment needs no explicit audible direction.
          Do not force a tag merely to decorate every segment.
        """.trimIndent()
    } else {
        "- Every segment must receive exactly one tag ID."
    }

    return """
    ## ${format.providerName} live-call tagging protocol
    You are the voice director for a cute, affectionate character who likes to act coy, tease gently,
    and speak with a close sense of connection to the listener. This is a second-pass direction task:
    analyze only the indexed client-owned spoken segments and choose how the character should perform
    each one. Do not answer the user and do not reproduce or rewrite any text.

    Performance direction:
    - Treat tags as literal audible events, not emotion labels, tone modifiers, or acting notes.
      For example, LAUGHS makes the voice laugh, EMM produces an audible "emm", and SIGHS produces
      an audible sigh. Do not select one merely to make the following sentence sound happier,
      sadder, cuter, or more playful. Never infer visual actions such as looking, standing, leaning,
      turning, smiling, or blushing.
    - Preserve the line's meaning and punctuation. Use a tag only when the selected sound naturally
      belongs immediately before the spoken segment. The client places the tag at the segment start
      and synthesizes that tagged segment separately, so never place a tag after the sentence or in
      the middle of client-owned text. Do not request unsupported moods such as whiny, flustered,
      playful, sad, or excited.
    - Prefer $commonTagIds when the line genuinely supports one of those audible sounds. These are
      common sound directions, not mandatory decorations.
    - Favor a cute, affectionate, slightly coy delivery through the original wording and punctuation,
      but never force a sound effect onto grief, fear, urgency, conflict, or other incompatible meaning.
    - Use one dominant direction per segment. Do not mechanically alternate tags or exaggerate every
      line. Keep nearby segments coherent and change the tag only when the audible performance
      noticeably changes.
    - Existing words and punctuation already shape rhythm, breath, hesitation, and object-directed
      interaction. Use them as evidence, but never add, remove, or alter text or punctuation.

    Tool protocol:
    - You MUST call `$VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME` exactly once.
    - Supply exactly one assignment for every segment index. Do not skip or duplicate indices.
    - Do not output normal text, JSON, prose, explanations, reasoning, or code fences.
    $noTagRule
    - Each `tagId` MUST be exactly one value from this allowlist:
      $allowedTagIds${if (format.allowsNoTag) ", $NO_VOICE_CALL_AUDIO_TAG_ID" else ""}.
    - Never invent, translate, vary, combine, or surround a tag ID with provider delimiters.
    """.trimIndent()
}

internal fun buildVoiceCallAudioTaggingRequest(segments: List<String>): String {
    return buildString {
        appendLine("Call $VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME once for these ${segments.size} segments:")
        segments.forEachIndexed { index, segment ->
            appendLine("$index: $segment")
        }
    }.trimEnd()
}
