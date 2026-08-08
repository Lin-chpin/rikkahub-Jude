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
    val noTagRule = when {
        format == VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8 -> """
        - For MiniMax Speech 2.8, choose one tag for every segment whenever a supported audible
          direction can fit, including segments with no leading interjection. Use
          $NO_VOICE_CALL_AUDIO_TAG_ID only when no supported tag is natural or the content would make
          an audible event inappropriate.
        """.trimIndent()

        format.allowsNoTag -> """
        - Use $NO_VOICE_CALL_AUDIO_TAG_ID when a segment needs no explicit audible direction.
          Do not force a tag merely to decorate every segment.
        """.trimIndent()

        else -> "- Every segment must receive exactly one tag ID."
    }

    val miniMaxInterjectionRule = if (format == VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8) {
        """
        - MiniMax Speech 2.8 treats these tags as replacements for spoken interjections and sound
          fillers, not as a closed list of exact words. The examples below are illustrative only;
          recognize the same audible meaning even when the Chinese characters, repetition, or drawn
          punctuation differ. For example, "嘿嘿/哈哈/嘻嘻，我好开心" is laugh-like and can use
          LAUGHS; "哎/唉/啊.....这样啊" is sigh-like and can use SIGHS; "嗯/嗯哼，这样啊" can
          use EMM. Preserve the meaningful sentence after the interjection.
        - When the segment begins with a short spoken sound or filler that has the same audible role
          as an allowed tag, choose that tag even if the exact wording is not listed in these
          examples. Do not require a literal dictionary match. Return that exact leading text in `replacementText`, including punctuation when it belongs to the filler; return an empty string when no replacement is needed. The client validates it as a prefix and removes it from the speech copy. The original reply remains unchanged for display.
        - If the segment has no leading spoken interjection, still choose one natural MiniMax tag whenever possible. Keep `replacementText` empty in that case; do not fall back to NONE merely because there is no word to replace.
        - Ignore any square-bracket directions (for example [laughs] or [pause]) that appear in a segment; the client removes them from the MiniMax speech copy before synthesis. Choose the tag from the audible meaning of the remaining text.
        - SIGHS is an actual audible sigh, not a request to pronounce the word "sighs". Do not keep
          the leading sigh-like filler in the MiniMax speech copy after selecting SIGHS.
        - Use this replacement only for an explicit audible interjection. Do not turn an abstract
          feeling such as "我很开心" into LAUGHS when no spoken sound word is present.
        """.trimIndent()
    } else {
        ""
    }
    val textOwnershipRule = if (format == VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8) {
        "- Keep the original text unchanged for display. The client may remove only a matched leading interjection from the MiniMax speech copy."
    } else {
        "- Existing words and punctuation already shape rhythm, breath, hesitation, and object-directed interaction. Use them as evidence, but never add, remove, or alter text or punctuation."
    }
    return """
    ## ${format.providerName} live-call tagging protocol
    You are the voice director for a cute, affectionate character who likes to act coy, tease gently,
    and speak with a close sense of connection to the listener. This is a second-pass direction task:
    analyze only the indexed client-owned spoken segments and choose how the character should perform
    each one. Do not answer the user and do not reproduce or rewrite any text.

    Performance direction:
    - Treat tags as literal audible events, not abstract mood labels or visual acting notes.
      For example, LAUGHS makes the voice laugh, EMM produces an audible "emm", and SIGHS produces
      an audible sigh. Select a tag when the segment contains a matching audible sound or leading spoken interjection, not merely to make the following sentence sound happier, sadder, cuter, or more playful. For MiniMax Speech 2.8, also select one natural tag for every segment whenever possible, even without a leading interjection; keep `replacementText` empty in that case. Never infer visual actions such as looking, standing, leaning,
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
    ${textOwnershipRule}

    ${miniMaxInterjectionRule}

    Tool protocol:
    - You MUST call `$VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME` exactly once.
    - Supply exactly one assignment for every segment index. Do not skip or duplicate indices.
    - Do not output normal text, prose, explanations, reasoning, or code fences.
    - If your runtime cannot emit a tool call, reply with ONLY the JSON envelope
      {"segments":[{"tagId":"...","text":"..."}]} covering every segment in order; the client
      accepts either a tool call or this JSON text.
    $noTagRule
    - Each `tagId` MUST be exactly one value from this allowlist:
      $allowedTagIds${if (format.allowsNoTag) ", $NO_VOICE_CALL_AUDIO_TAG_ID" else ""}.
    - For MiniMax Speech 2.8, every assignment also includes `replacementText`; use the exact leading spoken interjection or an empty string. Never put a rewritten sentence in this field.
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
