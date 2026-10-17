package me.rerere.rikkahub.ui.pages.chat

import me.rerere.tts.model.TTSResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal data class VoiceCallAudioRange(
    val text: String,
    val startPositionMs: Long,
    val endPositionMs: Long,
)

/** Audio arrives on IO while the call screen reads its position on the main thread. */
internal class VoiceCallAudioAlignment {
    private data class TimedCharacter(val char: Char, val startMs: Long, val endMs: Long)
    private val characters = mutableListOf<TimedCharacter>()

    @Synchronized
    fun append(raw: String): Boolean {
        val chunk = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return false
        val chars = chunk["chars"] as? JsonArray ?: return false
        val starts = chunk["char_start_times_ms"] as? JsonArray ?: return false
        val durations = chunk["char_durations_ms"] as? JsonArray ?: return false
        if (chars.isEmpty() || chars.size != starts.size || chars.size != durations.size) return false
        val added = mutableListOf<TimedCharacter>()
        var previousStart = -1L
        for (index in chars.indices) {
            val char = (chars[index] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            val start = (starts[index] as? JsonPrimitive)?.doubleOrNull ?: return false
            val duration = (durations[index] as? JsonPrimitive)?.doubleOrNull ?: return false
            val end = start + duration
            if (char.isEmpty() || !start.isFinite() || start < 0 || !duration.isFinite() || duration < 0 ||
                !end.isFinite() || start < previousStart
            ) return false
            char.forEach { added += TimedCharacter(it, start.toLong(), end.toLong()) }
            previousStart = start.toLong()
        }
        characters += added
        return true
    }

    @Synchronized
    fun visibleTextLength(text: String, positionMs: Long, includeUnfinishedTail: Boolean = true): Int? {
        val spoken = spokenCharacters()
        if (spoken.isEmpty()) return null
        var cursor = 0
        var visibleLength = 0
        var previousSentenceStart = -1L
        for (sentence in text.voiceCallDisplaySegments(includeUnfinishedTail)) {
            val clean = sentence.text.alignmentText()
            if (clean.isEmpty()) continue
            if (cursor >= spoken.size) break
            val available = minOf(clean.length, spoken.size - cursor)
            val sentenceCharacters = spoken.subList(cursor, cursor + available)
            if (sentenceCharacters.map { it.char }.joinToString("") != clean.take(available)) return null
            val sentenceStart = sentenceCharacters.minOf { it.startMs }
            if (sentenceStart < previousSentenceStart) return null
            previousSentenceStart = sentenceStart
            if (sentenceStart > positionMs) break
            visibleLength = sentence.endLength
            cursor += available
        }
        return visibleLength
    }

    @Synchronized
    fun ranges(text: String): List<VoiceCallAudioRange>? {
        val spoken = spokenCharacters()
        if (spoken.map { it.char }.joinToString("") != text.alignmentText()) return null
        var cursor = 0
        var previousSentenceStart = -1L
        return text.voiceCallDisplaySegments().map { sentence ->
            val clean = sentence.text.alignmentText()
            if (clean.isEmpty()) return null
            val end = cursor + clean.length
            val sentenceCharacters = spoken.getOrNull(cursor)?.let { spoken.subList(cursor, end) } ?: return null
            val startMs = sentenceCharacters.minOf { it.startMs }
            val endMs = sentenceCharacters.maxOf { it.endMs }
            if (endMs <= startMs || startMs < previousSentenceStart) return null
            previousSentenceStart = startMs
            cursor = end
            VoiceCallAudioRange(sentence.text, startMs, endMs)
        }.takeIf { it.isNotEmpty() }
    }

    private fun spokenCharacters(): List<TimedCharacter> {
        var insideTag = false
        return characters.filter { character ->
            when (character.char) {
                '[' -> { insideTag = true; false }
                ']' -> { insideTag = false; false }
                else -> !insideTag && !character.char.isWhitespace()
            }
        }
    }
}

internal fun alignedVoiceCallAudioRanges(
    text: String,
    response: TTSResponse,
    onRejected: (String) -> Unit = {},
): List<VoiceCallAudioRange>? {
    fun reject(reason: String): List<VoiceCallAudioRange>? {
        onRejected(reason)
        return null
    }
    if (response.metadata["alignment_complete"] != "true") return reject("unusable_alignment")
    val serializedChunks = response.metadata["alignment_chunks"] ?: return reject("missing_alignment_chunks")
    val chunks = runCatching { Json.parseToJsonElement(serializedChunks) as? JsonArray }
        .getOrNull() ?: return reject("invalid_alignment_chunks")
    val alignment = VoiceCallAudioAlignment()
    for (chunk in chunks) {
        if (!alignment.append(chunk.toString())) return reject("invalid_stream_timestamps")
    }
    return alignment.ranges(text) ?: reject("text_or_sentence_range_mismatch")
}

private fun String.alignmentText(): String =
    replace(Regex("\\[[^\\]\\r\\n]{1,80}]"), "").filterNot(Char::isWhitespace)

internal fun estimatedVoiceCallVisibleTextLength(text: String, positionMs: Long, includeUnfinishedTail: Boolean = true): Int {
    // ponytail: estimated pacing only when the provider omits usable alignment; replace with verified timestamps when available.
    var sentenceStartMs = 0L
    var visibleLength = 0
    for (sentence in text.voiceCallDisplaySegments(includeUnfinishedTail)) {
        if (sentenceStartMs > positionMs) break
        visibleLength = sentence.endLength
        sentenceStartMs += estimateVoiceCallDurationSeconds(sentence.text) * 1_000L
    }
    return visibleLength
}
