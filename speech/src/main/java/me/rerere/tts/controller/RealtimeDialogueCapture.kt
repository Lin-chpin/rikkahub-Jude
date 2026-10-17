package me.rerere.tts.controller

import java.io.ByteArrayOutputStream
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse

/** The player consumes on IO; cancellation snapshots only this turn's bytes and playback boundary. */
internal class RealtimeDialogueCapture {
    private val bytes = ByteArrayOutputStream()
    private val alignment = RealtimeDialogueAudioAlignment()
    private var format = AudioFormat.MP3
    private var sampleRate: Int? = 44_100
    private var chunkCount = 0
    private var timingId: String? = null

    @Synchronized
    fun append(chunk: AudioChunk): String? {
        format = chunk.format
        sampleRate = chunk.sampleRate ?: sampleRate
        bytes.write(chunk.data)
        chunkCount++
        timingId = timingId ?: chunk.metadata["timing_id"]
        return alignment.append(chunk)
    }

    @Synchronized
    fun snapshot(interruptedAtMs: Long? = null): TTSResponse = TTSResponse(
        audioData = bytes.toByteArray(),
        format = format,
        sampleRate = sampleRate,
        metadata = buildMap {
            timingId?.let { put("timing_id", it) }
            put("audio_chunk_count", chunkCount.toString())
            put("alignment_chunk_count", alignment.chunkCount.toString())
            put("alignment_complete", alignment.usable.toString())
            put("alignment_timebase", "stream")
            if (alignment.chunkCount > 0) put("alignment_chunks", alignment.serializedChunks)
            interruptedAtMs?.let { put("interrupted_at_ms", it.coerceAtLeast(0).toString()) }
        },
    )
}
