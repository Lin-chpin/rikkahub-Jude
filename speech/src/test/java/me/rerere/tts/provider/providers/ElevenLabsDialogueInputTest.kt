package me.rerere.tts.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevenLabsDialogueInputTest {
    @Test
    fun firstSentenceBoundaryIgnoresAudioTagsAndClausePunctuation() {
        assertEquals(-1, elevenLabsFirstSentenceEnd("[softly, slowly.]你好"))
        assertEquals("[softly]你好。".length, elevenLabsFirstSentenceEnd("[softly]你好。继续"))
        assertEquals("Hello!".length, elevenLabsFirstSentenceEnd("Hello! Next"))
        assertEquals(-1, elevenLabsFirstSentenceEnd("好的，我们"))
        assertEquals(-1, elevenLabsFirstSentenceEnd("Yes, I"))
        assertEquals(-1, elevenLabsFirstSentenceEnd("[softly, slowly]你好"))
        assertEquals(-1, elevenLabsFirstSentenceEnd("Hello"))
        assertEquals(-1, elevenLabsFirstSentenceEnd("[softly.]\n"))
    }
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
