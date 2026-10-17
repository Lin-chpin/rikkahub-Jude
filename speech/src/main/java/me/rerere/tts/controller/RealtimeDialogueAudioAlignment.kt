package me.rerere.tts.controller

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat

/** Converts ElevenLabs chunk-relative timings into the saved recording's time base. */
internal class RealtimeDialogueAudioAlignment {
    private val clock = Mp3StreamTimeline()
    private val chunks = mutableListOf<JsonObject>()
    private var invalidAlignment = false
    var rejectionReason: String? = null
        private set
    val chunkCount: Int get() = chunks.size
    val usable: Boolean get() = chunks.isNotEmpty() && !invalidAlignment && clock.valid
    val serializedChunks: String get() = JsonArray(chunks).toString()

    fun append(chunk: AudioChunk): String? {
        rejectionReason = null
        val offsetMs = clock.positionMs
        if (chunk.format != AudioFormat.MP3) return reject("unsupported_audio_format")
        clock.append(chunk.data)
        if (!clock.valid) return reject("invalid_mp3_frames")
        val raw = chunk.metadata["alignment"] ?: return null
        val value = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return reject("invalid_json")
        val chars = value["chars"] as? JsonArray ?: return reject("missing_chars")
        val starts = (value["char_start_times_ms"] ?: value["charStartTimesMs"]) as? JsonArray
            ?: return reject("missing_start_times")
        val durations = (value["char_durations_ms"] ?: value["charDurationsMs"]) as? JsonArray
            ?: return reject("missing_durations")
        if (chars.size != starts.size || chars.size != durations.size) return reject("array_length_mismatch")
        if (chars.isEmpty()) return null
        val spokenChars = mutableListOf<JsonPrimitive>()
        val absoluteStarts = mutableListOf<JsonPrimitive>()
        val spokenDurations = mutableListOf<JsonPrimitive>()
        var previousStart = -1.0
        for (index in chars.indices) {
            val char = (chars[index] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return reject("invalid_character")
            val start = (starts[index] as? JsonPrimitive)?.doubleOrNull ?: return reject("invalid_start")
            val duration = (durations[index] as? JsonPrimitive)?.doubleOrNull ?: return reject("invalid_duration")
            val end = offsetMs + start + duration
            if (!start.isFinite() || start < 0 || start < previousStart ||
                !duration.isFinite() || duration < 0 || !end.isFinite() || end >= Long.MAX_VALUE.toDouble()
            ) return reject("invalid_timing")
            // Empty character entries carry silence, not text; the audio clock already retains their duration.
            if (char.isNotEmpty()) {
                spokenChars += JsonPrimitive(char)
                absoluteStarts += JsonPrimitive(offsetMs + start)
                spokenDurations += JsonPrimitive(duration)
            }
            previousStart = start
        }
        if (spokenChars.isEmpty()) return null
        val normalized = buildJsonObject {
            put("chars", JsonArray(spokenChars))
            put("char_start_times_ms", JsonArray(absoluteStarts))
            put("char_durations_ms", JsonArray(spokenDurations))
        }
        chunks += normalized
        return normalized.toString()
    }

    private fun reject(reason: String): String? {
        invalidAlignment = true
        rejectionReason = reason
        return null
    }
}
