package me.rerere.tts.provider.providers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class FishAudioMessagePackTest {
    @Test
    fun roundTripsFishAudioControlAndAudioFrames() {
        val fixedTextFrame = byteArrayOf(
            0x82.toByte(), 0xa5.toByte(), 'e'.code.toByte(), 'v'.code.toByte(), 'e'.code.toByte(),
            'n'.code.toByte(), 't'.code.toByte(), 0xa4.toByte(), 't'.code.toByte(), 'e'.code.toByte(),
            'x'.code.toByte(), 't'.code.toByte(), 0xa4.toByte(), 't'.code.toByte(), 'e'.code.toByte(),
            'x'.code.toByte(), 't'.code.toByte(), 0xa2.toByte(), 'h'.code.toByte(), 'i'.code.toByte(),
        )
        val textEvent = mapOf("event" to "text", "text" to "hi")
        assertArrayEquals(fixedTextFrame, encodeFishAudioEvent(textEvent))
        assertEquals(textEvent, decodeFishAudioEvent(fixedTextFrame))

        val start = mapOf(
            "event" to "start",
            "request" to mapOf(
                "text" to "",
                "reference_id" to "voice-id",
                "prosody" to mapOf("speed" to 1.25),
            ),
        )
        val decodedStart = decodeFishAudioEvent(encodeFishAudioEvent(start))
        val request = decodedStart["request"] as Map<*, *>
        val prosody = request["prosody"] as Map<*, *>
        assertEquals("start", decodedStart["event"])
        assertEquals("voice-id", request["reference_id"])
        assertEquals(1.25, prosody["speed"])

        val audio = byteArrayOf(0, 1, 0x7f, 0xff.toByte())
        val decodedAudio = decodeFishAudioEvent(
            encodeFishAudioEvent(mapOf("event" to "audio", "audio" to audio)),
        )
        assertEquals("audio", decodedAudio["event"])
        assertArrayEquals(audio, decodedAudio["audio"] as ByteArray)
    }

    @Test
    fun rejectsTruncatedOrUnsupportedFrames() {
        assertThrows(IOException::class.java) { decodeFishAudioEvent(byteArrayOf(0x81.toByte())) }
        assertThrows(IOException::class.java) { decodeFishAudioEvent(byteArrayOf(0xc0.toByte())) }
    }
}
