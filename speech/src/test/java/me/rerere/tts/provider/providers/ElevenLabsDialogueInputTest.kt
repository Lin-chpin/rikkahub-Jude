package me.rerere.tts.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevenLabsDialogueInputTest {
    @Test
    fun sendsWholeAvailableTextAndOnlyBuffersIncompleteTagsOrUnicodeCharacters() {
        val reply = "a".repeat(200) + "[laughs]😀" + "b".repeat(200)
        assertEquals(reply.length, elevenLabsDialogueInputReadyEnd(reply))
        assertEquals(5, elevenLabsDialogueInputReadyEnd("Hello[lau"))
        assertEquals(0, elevenLabsDialogueInputReadyEnd("[lau"))
        assertEquals(1, elevenLabsDialogueInputReadyEnd("a\uD83D"))
        assertEquals(3, elevenLabsDialogueInputReadyEnd("a😀"))
        assertEquals(0, elevenLabsDialogueInputReadyEnd(""))
    }
}
