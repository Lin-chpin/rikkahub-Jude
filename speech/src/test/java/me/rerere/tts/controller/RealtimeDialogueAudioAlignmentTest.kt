package me.rerere.tts.controller

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeDialogueAudioAlignmentTest {
    private fun frame(): ByteArray = ByteArray(417).apply {
        this[0] = 0xff.toByte()
        this[1] = 0xfb.toByte()
        this[2] = 0x90.toByte()
    }

    private fun chunk(raw: String? = null): AudioChunk = AudioChunk(
        data = frame(), format = AudioFormat.MP3,
        metadata = raw?.let { mapOf("alignment" to it) } ?: emptyMap(),
    )

    @Test
    fun localTimesIncludeUnalignedAudioAndDoNotRequireAlignmentOnEncoderTail() {
        val alignment = RealtimeDialogueAudioAlignment()
        assertTrue(alignment.append(chunk("""{"chars":["你","。"],"char_start_times_ms":[0,10],"char_durations_ms":[10,0]}""")) != null)
        assertNull(alignment.append(chunk()))
        val second = alignment.append(chunk("""{"chars":["好","！"],"charStartTimesMs":[0,10],"charDurationsMs":[10,0]}"""))!!
        val starts = (Json.parseToJsonElement(second) as JsonObject)["char_start_times_ms"] as JsonArray
        assertEquals(2 * 1152 * 1_000.0 / 44_100, (starts[0] as JsonPrimitive).double, 0.001)
        assertNull(alignment.append(chunk("""{"chars":[],"char_start_times_ms":[],"char_durations_ms":[]}""")))
        assertTrue(alignment.usable)
        assertEquals(2, alignment.chunkCount)
    }

    @Test
    fun frameClockHandlesSplitHeadersTagsAndSeekFrames() {
        val clock = Mp3StreamTimeline()
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0)
        val info = frame().apply { "Info".toByteArray().copyInto(this, 36) }
        val data = id3 + info + frame() + frame()
        clock.append(data.copyOfRange(0, 5))
        clock.append(data.copyOfRange(5, 429))
        clock.append(data.copyOfRange(429, data.size))
        assertTrue(clock.valid)
        assertEquals(2 * 1152 * 1_000.0 / 44_100, clock.positionMs, 0.001)
    }

    @Test
    fun malformedAlignmentCannotProduceTrustedRangesButSilenceEntriesAreAllowed() {
        val alignment = RealtimeDialogueAudioAlignment()
        assertNull(alignment.append(chunk("""{"chars":[""],"char_start_times_ms":[0],"char_durations_ms":[20]}""")))
        assertNull(alignment.rejectionReason)
        assertNull(alignment.append(chunk("""{"chars":["错"],"char_start_times_ms":[],"char_durations_ms":[20]}""")))
        assertEquals("array_length_mismatch", alignment.rejectionReason)
        assertFalse(alignment.usable)
        assertEquals(0, alignment.chunkCount)
    }

    @Test
    fun packetBoundaryTimestampOverlapDoesNotDiscardTheWholeAlignment() {
        val alignment = RealtimeDialogueAudioAlignment()
        assertTrue(alignment.append(chunk("""{"chars":["你"],"char_start_times_ms":[50],"char_durations_ms":[30]}""")) != null)
        val second = alignment.append(chunk("""{"chars":["好"],"char_start_times_ms":[0],"char_durations_ms":[100]}"""))!!
        assertTrue(alignment.usable)
        assertEquals(2, alignment.chunkCount)
        val starts = (Json.parseToJsonElement(second) as JsonObject)["char_start_times_ms"] as JsonArray
        assertEquals(26.122, starts.single().let { (it as JsonPrimitive).double }, 0.001)
    }
}
