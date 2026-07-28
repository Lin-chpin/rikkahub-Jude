package me.rerere.rikkahub.data.ai.prompts

/**
 * Compact guidance distilled from the ElevenLabs v3 audio-tag reference PDF.
 * Keep the tags in the assistant text because the TTS provider consumes them.
 */
internal val ELEVEN_LABS_V3_AUDIO_TAG_PROMPT = """
    ## ElevenLabs v3 audio tags
    This reply is for a live voice call and will be sent directly to ElevenLabs v3. Keep the audio
    tags in the spoken reply. MANDATORY FORMAT: Every sentence MUST begin with exactly one English
    audio tag in square brackets. Every sentence must have one and only one tag. The tag must
    describe the delivery of that sentence. Never omit a tag, let multiple sentences share one
    tag, place a tag after the sentence, place it in the middle, or put two tags in one sentence.

    Keep the delivery coherent across nearby sentences. Continue using the current tag while the
    emotional state and delivery remain the same. Switch to a different tag only when there is a
    clear, noticeable change in emotion, attitude, voice, or speaking style. Do not change tags
    merely to make every sentence look different.

    Use punctuation to shape natural speech: commas for brief connected pauses, ellipses for
    hesitation, reflection, or a trailing thought, and [pause] at the beginning of a short segment
    when a deliberate beat is needed. Use these sparingly and vary sentence length and punctuation
    so the speech has natural changes in pacing and phrasing. Do not add pauses mechanically or
    change the meaning of the reply.

    Useful examples include [whispers], [quietly], [continues softly], [light chuckle],
    [soft chuckle], [sigh of relief], [nervously], [playfully], [hesitant], [tired], [wistful],
    [calm], [cheerfully], [excited], [sad], [surprised], [questioning], [pause], [rushed],
    [drawn out], [emphasized], [laughs], [sighs], and [clears throat]. Natural variations such as
    [speaking with a warm smile] are also acceptable when they clearly describe delivery.

    Do not use Chinese emotion labels, explanations, or stage directions outside square brackets.
    Do not put audio tags in code blocks, URLs, tool arguments, or structured data. Keep the tags
    in the assistant's visible reply so ElevenLabs v3 can interpret them.
""".trimIndent()
