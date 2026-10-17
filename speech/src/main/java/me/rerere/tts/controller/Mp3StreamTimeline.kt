package me.rerere.tts.controller

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.MpegAudioUtil

/** Counts encoded audio samples, including pauses in packets with no character alignment. */
@OptIn(UnstableApi::class)
internal class Mp3StreamTimeline {
    private val header = MpegAudioUtil.Header()
    private var pending = byteArrayOf()
    private var tagBytesRemaining = 0
    private var firstFrame = true
    private var durationMs = 0.0
    var valid = true
        private set
    val positionMs: Double get() = durationMs

    fun append(bytes: ByteArray) {
        if (!valid || bytes.isEmpty()) return
        val data = pending + bytes
        var cursor = 0
        while (cursor < data.size) {
            if (tagBytesRemaining > 0) {
                val skipped = minOf(tagBytesRemaining, data.size - cursor)
                cursor += skipped
                tagBytesRemaining -= skipped
                continue
            }
            if (data.size - cursor < 4) break
            if (data[cursor] == 'I'.code.toByte() && data[cursor + 1] == 'D'.code.toByte() && data[cursor + 2] == '3'.code.toByte()) {
                if (data.size - cursor < 10) break
                if ((6..9).any { data[cursor + it].toInt() and 0x80 != 0 }) {
                    valid = false
                    return
                }
                val size = (6..9).fold(0) { value, index -> (value shl 7) or (data[cursor + index].toInt() and 0x7f) }
                tagBytesRemaining = 10 + size + if (data[cursor + 5].toInt() and 0x10 != 0) 10 else 0
                continue
            }
            val bits = (0..3).fold(0) { value, index -> (value shl 8) or (data[cursor + index].toInt() and 0xff) }
            if (!header.setForHeaderData(bits) || header.mimeType != MimeTypes.AUDIO_MPEG) {
                valid = false
                return
            }
            if (data.size - cursor < header.frameSize) break
            // ExoPlayer skips an initial Xing/Info/VBRI seek frame; it is not audible audio.
            val xingOffset = if (header.version == 3) {
                if (header.channels == 1) 21 else 36
            } else {
                if (header.channels == 1) 13 else 21
            }
            fun marker(offset: Int): String = if (offset + 4 <= header.frameSize) {
                String(data, cursor + offset, 4, Charsets.US_ASCII)
            } else ""
            val seekFrame = firstFrame && (marker(xingOffset) in setOf("Xing", "Info") || marker(36) == "VBRI")
            if (!seekFrame) durationMs += header.samplesPerFrame * 1_000.0 / header.sampleRate
            firstFrame = false
            cursor += header.frameSize
        }
        pending = data.copyOfRange(cursor, data.size)
    }
}
