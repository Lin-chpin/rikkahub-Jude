package me.rerere.rikkahub.data.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val VOICE_CALL_METADATA_KEY = "voiceCallAudioTags"
private const val LEGACY_VOICE_CALL_METADATA_KEY = "elevenLabsV3VoiceCall"
private const val SPEECH_TEXT_KEY = "speechText"
private const val DISPLAY_TEXT_KEY = "displayText"
private const val SEGMENTS_KEY = "segments"
private const val TAG_ID_KEY = "tagId"
private const val TEXT_KEY = "text"
private const val TAG_FORMAT_KEY = "tagFormat"
private const val SELECTION_SOURCE_KEY = "selectionSource"
private const val FALLBACK_REASON_KEY = "fallbackReason"

private val voiceCallResponseJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val jsonFenceRegex = Regex("""(?s)```(?:json)?\s*(.*?)\s*```""", RegexOption.IGNORE_CASE)
private val bracketedDirectionRegex = Regex("""\[[^\]\r\n]{1,80}]""")
private val legacyBracketedAudioTagRegex = Regex("""\[([A-Za-z][A-Za-z0-9 ,.'!?-]{0,78})][ \t]*""")
private val legacyAudioDirectionKeywords = setOf(
    "whisper", "quiet", "soft", "chuckle", "sigh", "nervous", "playful", "hesitant",
    "whiny", "flustered", "tired", "wistful", "calm", "cheerful", "excited", "sad",
    "surprised", "questioning",
    "pause", "rushed", "drawn", "emphasized", "laugh", "throat", "speaking", "voice",
    "smile", "warmly", "gently", "loudly", "slowly",
)
private val malformedJsonTextFieldRegex =
    Regex(""""text"\s*:\s*("(?:\\.|[^"\\])*")""", RegexOption.IGNORE_CASE)

private val allowedLegacyAudioTagRegex = Regex(
    pattern = VoiceCallAudioTag.entries.joinToString(
        prefix = """\[\s*(""",
        postfix = """)\s*]""",
        separator = "|",
    ) { Regex.escape(it.word) },
    option = RegexOption.IGNORE_CASE,
)

@Serializable
private data class VoiceCallResponseEnvelope(
    val segments: List<VoiceCallResponseSegment> = emptyList(),
    val tagIds: List<String> = emptyList(),
)

@Serializable
private data class VoiceCallResponseSegment(
    val tagId: String = "",
    val text: String = "",
)

internal data class ParsedVoiceCallResponse(
    val visibleText: String,
    val speechText: String,
    val displayText: String,
    val segments: List<ParsedVoiceCallSegment>,
)

internal data class ParsedVoiceCallSegment(
    val tag: VoiceCallAudioTag?,
    val text: String,
    val selectionSource: VoiceCallTagSelectionSource,
    val fallbackReason: VoiceCallTaggingFallbackReason? = null,
    val replacementText: String? = null,
)

internal enum class VoiceCallTagSelectionSource {
    STRUCTURED,
    LEGACY,
    FALLBACK,
}

internal enum class VoiceCallTaggingFallbackReason(val displayName: String) {
    REQUEST_ERROR("request_error"),
    EMPTY_RESPONSE("empty_response"),
    INVALID_RESPONSE("invalid_response"),
    UNKNOWN_TAG("unknown_tag"),
    MISSING_TOOL_CALL("missing_tool_call"),
    INVALID_TOOL_ARGUMENTS("invalid_tool_arguments"),
}

internal fun parseVoiceCallAudioTagResponse(
    rawResponse: String,
    format: VoiceCallAudioTagFormat = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
): ParsedVoiceCallResponse {
    val envelope = extractJsonObject(rawResponse)?.let { jsonText ->
        runCatching {
            voiceCallResponseJson.decodeFromString<VoiceCallResponseEnvelope>(jsonText)
        }.getOrNull()
    }
    val structuredSegments = envelope
        ?.segments
        ?.mapNotNull { segment ->
            val cleanText = segment.text.removeModelAudioDirections().trim()
            cleanText.takeIf { it.isNotBlank() }?.let {
                val selectedTag = VoiceCallAudioTag.fromId(segment.tagId)
                ParsedVoiceCallSegment(
                    tag = selectedTag ?: format.fallbackTag(),
                    text = it,
                    selectionSource = if (selectedTag != null) {
                        VoiceCallTagSelectionSource.STRUCTURED
                    } else {
                        VoiceCallTagSelectionSource.FALLBACK
                    },
                    fallbackReason = if (selectedTag == null) {
                        VoiceCallTaggingFallbackReason.UNKNOWN_TAG
                    } else {
                        null
                    },
                )
            }
        }
        .orEmpty()

    val parsedSegments = structuredSegments
        .ifEmpty { parseLegacyTaggedSegments(rawResponse) }
        .ifEmpty {
            listOf(
                ParsedVoiceCallSegment(
                    tag = format.fallbackTag(),
                    text = extractFallbackVisibleText(rawResponse),
                    selectionSource = VoiceCallTagSelectionSource.FALLBACK,
                    fallbackReason = VoiceCallTaggingFallbackReason.INVALID_RESPONSE,
                )
            )
        }
        .filter { it.text.isNotBlank() }

    val safeSegments = parsedSegments.ifEmpty {
        listOf(
            ParsedVoiceCallSegment(
                tag = format.fallbackTag(),
                text = "……",
                selectionSource = VoiceCallTagSelectionSource.FALLBACK,
                fallbackReason = VoiceCallTaggingFallbackReason.INVALID_RESPONSE,
            )
        )
    }
    return buildParsedVoiceCallResponse(safeSegments, format)
}

private fun buildParsedVoiceCallResponse(
    segments: List<ParsedVoiceCallSegment>,
    format: VoiceCallAudioTagFormat,
): ParsedVoiceCallResponse {
    return ParsedVoiceCallResponse(
        visibleText = segments.joinToString("\n") { it.text },
        speechText = segments.joinToString("\n") { segment ->
            val speechSegmentText = segment.voiceCallSpeechText(format)
            segment.tag?.let { "${format.renderForSpeech(it.word)}$speechSegmentText" } ?: speechSegmentText
        },
        displayText = segments.joinToString("\n") { segment ->
            val displaySegmentText = segment.voiceCallSpeechText(format)
            val label = when (segment.selectionSource) {
                VoiceCallTagSelectionSource.STRUCTURED,
                VoiceCallTagSelectionSource.LEGACY,
                    -> segment.tag?.word

                VoiceCallTagSelectionSource.FALLBACK -> buildString {
                    append(segment.tag?.word ?: "no tag")
                    append(" fallback")
                    segment.fallbackReason?.let {
                        append(':')
                        append(it.displayName)
                    }
                }
            }
            label?.let { "${format.renderForSpeech(it)}$displaySegmentText" } ?: displaySegmentText
        },
        segments = segments,
    )
}

private fun ParsedVoiceCallSegment.voiceCallSpeechText(
    format: VoiceCallAudioTagFormat,
): String {
    // MiniMax renders round-bracket tags itself; strip square-bracket directions the
    // primary model may have written so they never reach the MiniMax speech copy.
    val cleanText = if (format == VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8) {
        text.removeModelAudioDirections().trim()
    } else {
        text
    }
    return cleanText.removeLeadingVoiceCallInterjection(
        tag = tag,
        format = format,
        replacementText = replacementText,
    )
}

private fun String.removeLeadingVoiceCallInterjection(
    tag: VoiceCallAudioTag?,
    format: VoiceCallAudioTagFormat,
    replacementText: String? = null,
): String {
    if (format != VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8) return this

    if (!replacementText.isNullOrBlank()) {
        removeExactLeadingVoiceCallInterjection(replacementText)?.let { return it }
    }

    val aliases = tag?.leadingInterjections.orEmpty()
    if (aliases.isEmpty()) return this

    val aliasPattern = aliases
        .sortedByDescending(String::length)
        .joinToString("|") { Regex.escape(it) }
    val leadingInterjectionRegex = Regex(
        """^\s*(?:$aliasPattern)(?=$|[\s，,、。！？!?…~～.])[\s，,、。！？!?…~～.]*"""
    )
    return replaceFirst(leadingInterjectionRegex, "").trimStart()
}

private fun String.removeExactLeadingVoiceCallInterjection(
    replacementText: String,
): String? {
    val candidate = trimStart()
    if (!candidate.startsWith(replacementText)) return null

    val remainingText = candidate.removePrefix(replacementText)
    if (remainingText.isBlank()) return null
    val replacementEndsWithSeparator =
        replacementText.lastOrNull()?.isVoiceCallInterjectionSeparator() == true
    if (!replacementEndsWithSeparator &&
        !remainingText.first().isVoiceCallInterjectionSeparator()
    ) {
        return null
    }

    return remainingText.trimStart { character ->
        character.isWhitespace() || character.isVoiceCallInterjectionSeparator()
    }
}

private fun Char.isVoiceCallInterjectionSeparator(): Boolean {
    return isWhitespace() || this in setOf(
        '\uFF0C', ',', '\u3002', '.', '!', '\uFF01', '?', '\uFF1F',
        ';', '\uFF1B', ':', '\uFF1A', '\u3001', '\u2026', '~', '\uFF5E',
    )
}
internal fun UIMessage.toVoiceCallAudioTagPresentation(
    format: VoiceCallAudioTagFormat = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
): UIMessage {
    if (finishedAt == null) {
        return copy(
            parts = parts.map { part ->
                if (part is UIMessagePart.Text) part.copy(text = "") else part
            }
        )
    }

    val rawText = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (rawText.isBlank()) return this

    val parsed = parseVoiceCallAudioTagResponse(rawText, format)
    var replacedText = false
    return copy(
        parts = parts.map { part ->
            if (part is UIMessagePart.Text && !replacedText) {
                replacedText = true
                part.copy(
                    text = parsed.visibleText,
                    metadata = part.metadata.withVoiceCallSpeechMetadata(parsed, format),
                )
            } else if (part is UIMessagePart.Text) {
                part.copy(text = "")
            } else {
                part
            }
        }
    )
}


internal fun UIMessage.withSelectedVoiceCallAudioTagIds(
    selectedTagIds: List<String?>?,
    format: VoiceCallAudioTagFormat,
    selectionFailureReason: VoiceCallTaggingFallbackReason? = null,
): UIMessage {
    return withSelectedVoiceCallAudioTagAssignments(
        selectedAssignments = selectedTagIds?.map { VoiceCallAudioTagAssignment(it) },
        format = format,
        selectionFailureReason = selectionFailureReason,
    )
}

internal fun UIMessage.withSelectedVoiceCallAudioTagAssignments(
    selectedAssignments: List<VoiceCallAudioTagAssignment>?,
    format: VoiceCallAudioTagFormat,
    taggingSegmentIndexes: List<Int>? = null,
    selectionFailureReason: VoiceCallTaggingFallbackReason? = null,
): UIMessage {
    val originalText = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (originalText.isBlank()) return this

    val originalSegments = splitVoiceCallAudioTaggingSegments(originalText)
    val assignmentsForOriginalSegments = expandVoiceCallAudioTagAssignments(
        originalSegmentCount = originalSegments.size,
        selectedAssignments = selectedAssignments,
        taggingSegmentIndexes = taggingSegmentIndexes,
    )
    val acceptedTagging = when {
        selectionFailureReason != null && taggingSegmentIndexes != null ->
            fallbackVoiceCallResponseForSegments(
                originalSegments,
                taggingSegmentIndexes,
                selectionFailureReason,
                format,
            )

        selectionFailureReason != null ->
            fallbackVoiceCallResponse(originalText, selectionFailureReason, format)

        else -> buildVoiceCallResponseFromSelectedAssignments(
            originalSegments = originalSegments,
            selectedAssignments = assignmentsForOriginalSegments,
            format = format,
        ) ?: taggingSegmentIndexes?.let { indexes ->
            fallbackVoiceCallResponseForSegments(
                originalSegments,
                indexes,
                VoiceCallTaggingFallbackReason.INVALID_TOOL_ARGUMENTS,
                format,
            )
        } ?: fallbackVoiceCallResponse(
            originalText,
            VoiceCallTaggingFallbackReason.INVALID_TOOL_ARGUMENTS,
            format,
        )
    }

    var taggedTextPart = false
    return copy(
        parts = parts.map { part ->
            if (part is UIMessagePart.Text && !taggedTextPart) {
                taggedTextPart = true
                part.copy(
                    text = originalText,
                    metadata = part.metadata.withVoiceCallSpeechMetadata(acceptedTagging, format),
                )
            } else if (part is UIMessagePart.Text) {
                part.copy(text = "")
            } else {
                part
            }
        }
    )
}

internal fun UIMessage.voiceCallSpeechTextOrPlainText(): String {
    return voiceCallMetadataText(SPEECH_TEXT_KEY) ?: toText()
}

internal fun UIMessage.hasVoiceCallAudioTagMetadata(): Boolean {
    return parts.any { part ->
        part is UIMessagePart.Text && part.metadata?.containsKey(VOICE_CALL_METADATA_KEY) == true
    }
}

internal fun UIMessage.voiceCallAudioTagAssignmentsOrEmpty(): List<VoiceCallAudioTagAssignment?> {
    val segments = parts.filterIsInstance<UIMessagePart.Text>()
        .firstNotNullOfOrNull { part ->
            part.metadata
                ?.get(VOICE_CALL_METADATA_KEY)
                ?.let { metadata -> runCatching { metadata.jsonObject }.getOrNull() }
                ?.get(SEGMENTS_KEY)
                ?.let { segments -> runCatching { segments.jsonArray }.getOrNull() }
        }
        ?: return emptyList()
    return segments.map { segment ->
        segment.jsonObject[TAG_ID_KEY]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { tagId -> VoiceCallAudioTagAssignment(tagId = tagId) }
    }
}

/** Applies only the assignments already returned by the tagger. */
internal fun UIMessage.withIncrementalVoiceCallAudioTagAssignments(
    assignments: Map<Int, VoiceCallAudioTagAssignment?>,
    format: VoiceCallAudioTagFormat,
): UIMessage {
    if (assignments.isEmpty()) return this
    val originalText = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (originalText.isBlank()) return this

    val originalSegments = splitVoiceCallAudioTaggingSegments(originalText)
    val parsedSegments = originalSegments.mapIndexed { index, text ->
        val assignment = assignments[index]
        ParsedVoiceCallSegment(
            tag = assignment?.tagId?.let(VoiceCallAudioTag::fromId),
            text = text,
            selectionSource = VoiceCallTagSelectionSource.STRUCTURED,
            replacementText = assignment?.replacementText,
        )
    }
    val displayProjection = buildParsedVoiceCallResponse(parsedSegments, format)
    val contiguousSpeechSegments = buildList {
        for (index in originalSegments.indices) {
            if (index !in assignments) break
            add(parsedSegments[index])
        }
    }
    val speechProjection = buildParsedVoiceCallResponse(contiguousSpeechSegments, format)
    val acceptedProjection = displayProjection.copy(speechText = speechProjection.speechText)

    var taggedTextPart = false
    return copy(
        parts = parts.map { part ->
            if (part is UIMessagePart.Text && !taggedTextPart) {
                taggedTextPart = true
                part.copy(
                    text = originalText,
                    metadata = part.metadata.withVoiceCallSpeechMetadata(acceptedProjection, format),
                )
            } else if (part is UIMessagePart.Text) {
                part.copy(text = "")
            } else {
                part
            }
        }
    )
}

internal fun UIMessage.voiceCallDisplayTextOrPlainText(): String {
    return voiceCallMetadataText(DISPLAY_TEXT_KEY)
        ?: voiceCallMetadataText(SPEECH_TEXT_KEY)
        ?: toText()
}

private fun UIMessage.voiceCallMetadataText(key: String): String? {
    return parts.filterIsInstance<UIMessagePart.Text>()
        .firstNotNullOfOrNull { part ->
            listOf(VOICE_CALL_METADATA_KEY, LEGACY_VOICE_CALL_METADATA_KEY)
                .firstNotNullOfOrNull { metadataKey ->
                    part.metadata
                        ?.get(metadataKey)
                        ?.let { metadata -> runCatching { metadata.jsonObject }.getOrNull() }
                        ?.get(key)
                        ?.jsonPrimitive
                        ?.contentOrNull
                }
        }
}

internal fun UIMessage.withoutVoiceCallAudioTagsForNormalContext(): UIMessage {
    if (role != MessageRole.ASSISTANT) return this
    return copy(parts = parts.map(UIMessagePart::withoutVoiceCallAudioTagData))
}

internal fun List<UIMessagePart>.withoutVoiceCallAudioTagsForChatDisplay(): List<UIMessagePart> {
    return map(UIMessagePart::withoutVoiceCallAudioTagData)
}

private fun UIMessagePart.withoutVoiceCallAudioTagData(): UIMessagePart {
    return when (this) {
        is UIMessagePart.Text -> copy(
            text = text.stripKnownVoiceCallAudioTags(),
            metadata = metadata?.withoutVoiceCallMetadata(),
        )

        is UIMessagePart.Tool -> copy(
            output = output.map(UIMessagePart::withoutVoiceCallAudioTagData),
            metadata = metadata?.withoutVoiceCallMetadata(),
        )

        else -> this
    }
}

private fun JsonObject?.withVoiceCallSpeechMetadata(
    parsed: ParsedVoiceCallResponse,
    format: VoiceCallAudioTagFormat,
): JsonObject {
    val voiceCallMetadata = buildJsonObject {
        put(SPEECH_TEXT_KEY, parsed.speechText)
        put(DISPLAY_TEXT_KEY, parsed.displayText)
        put(TAG_FORMAT_KEY, format.name)
        put(
            SEGMENTS_KEY,
            buildJsonArray {
                parsed.segments.forEach { segment ->
                    add(
                        buildJsonObject {
                            segment.tag?.let { put(TAG_ID_KEY, it.id) }
                            put(TEXT_KEY, segment.text)
                            put(SELECTION_SOURCE_KEY, segment.selectionSource.name.lowercase())
                            segment.fallbackReason?.let {
                                put(FALLBACK_REASON_KEY, it.displayName)
                            }
                        }
                    )
                }
            }
        )
    }
    return JsonObject(orEmpty() + (VOICE_CALL_METADATA_KEY to voiceCallMetadata))
}

private fun JsonObject.withoutVoiceCallMetadata(): JsonObject? {
    return JsonObject(this - VOICE_CALL_METADATA_KEY - LEGACY_VOICE_CALL_METADATA_KEY)
        .takeIf { it.isNotEmpty() }
}

private fun parseLegacyTaggedSegments(rawResponse: String): List<ParsedVoiceCallSegment> {
    val matches = allowedLegacyAudioTagRegex.findAll(rawResponse).toList()
    if (matches.isEmpty()) return emptyList()

    return buildList {
        val preamble = rawResponse.substring(0, matches.first().range.first)
            .removeModelAudioDirections()
            .trim()
        if (preamble.isNotBlank()) {
            add(
                ParsedVoiceCallSegment(
                    tag = VoiceCallAudioTag.BREATH,
                    text = preamble,
                    selectionSource = VoiceCallTagSelectionSource.FALLBACK,
                    fallbackReason = VoiceCallTaggingFallbackReason.INVALID_RESPONSE,
                )
            )
        }

        matches.forEachIndexed { index, match ->
            val selectedTag = VoiceCallAudioTag.fromWord(match.groupValues[1])
                ?: return@forEachIndexed
            val textEndExclusive = matches.getOrNull(index + 1)?.range?.first ?: rawResponse.length
            val text = rawResponse.substring(match.range.last + 1, textEndExclusive)
                .removeModelAudioDirections()
                .trim()
            if (text.isNotBlank()) {
                add(
                    ParsedVoiceCallSegment(
                        tag = selectedTag,
                        text = text,
                        selectionSource = VoiceCallTagSelectionSource.LEGACY,
                    )
                )
            }
        }
    }
}

internal fun selectVoiceCallAudioTaggingSegmentIndexes(
    segments: List<String>,
    englishOnly: Boolean,
): List<Int> {
    return segments.indices.filter { segmentIndex ->
        !englishOnly || segments[segmentIndex].isEnglishOnlyVoiceCallSegment()
    }
}

private fun String.isEnglishOnlyVoiceCallSegment(): Boolean {
    var hasLatinLetter = false
    forEach { character ->
        if (!character.isLetter()) return@forEach
        if (Character.UnicodeScript.of(character.code) != Character.UnicodeScript.LATIN) {
            return false
        }
        hasLatinLetter = true
    }
    return hasLatinLetter
}

internal fun splitVoiceCallAudioTaggingSegments(text: String): List<String> {
    val segments = mutableListOf<String>()
    var start = 0
    var index = 0
    while (index < text.length) {
        if (text[index] == '.' && text.getOrNull(index + 1) == '.') {
            while (index < text.length && text[index] == '.') index++
            continue
        }
        if (text[index].isVoiceCallAudioTagSegmentBoundary()) {
            var endExclusive = index + 1
            while (endExclusive < text.length && text[endExclusive].isVoiceCallAudioTagSegmentBoundary()) {
                endExclusive++
            }
            text.substring(start, endExclusive).trim().takeIf { it.isNotBlank() }?.let(segments::add)
            start = endExclusive
            index = endExclusive
        } else {
            index++
        }
    }
    text.substring(start).trim().takeIf { it.isNotBlank() }?.let(segments::add)
    return segments.ifEmpty { listOf(text.trim()) }.filter { it.isNotBlank() }
}

private fun Char.isVoiceCallAudioTagSegmentBoundary(): Boolean {
    return this == '。' || this == '.' || this == '！' || this == '？' || this == '!' || this == '?' ||
        this == '；' || this == ';' || this == '\n'
}

private fun buildVoiceCallResponseFromSelectedAssignments(
    originalSegments: List<String>,
    selectedAssignments: List<VoiceCallAudioTagAssignment>?,
    format: VoiceCallAudioTagFormat,
): ParsedVoiceCallResponse? {
    if (selectedAssignments == null || selectedAssignments.size != originalSegments.size) return null

    return buildParsedVoiceCallResponse(
        originalSegments.mapIndexed { index, text ->
            val assignment = selectedAssignments[index]
            val selectedTag = assignment.tagId?.let { VoiceCallAudioTag.fromId(it) ?: return null }
            ParsedVoiceCallSegment(
                tag = selectedTag,
                text = text,
                selectionSource = VoiceCallTagSelectionSource.STRUCTURED,
                replacementText = assignment.replacementText,
            )
        },
        format,
    )
}

private fun expandVoiceCallAudioTagAssignments(
    originalSegmentCount: Int,
    selectedAssignments: List<VoiceCallAudioTagAssignment>?,
    taggingSegmentIndexes: List<Int>?,
): List<VoiceCallAudioTagAssignment>? {
    if (taggingSegmentIndexes == null) return selectedAssignments
    if (selectedAssignments == null || selectedAssignments.size != taggingSegmentIndexes.size) return null
    if (taggingSegmentIndexes.distinct().size != taggingSegmentIndexes.size ||
        taggingSegmentIndexes.any { it !in 0 until originalSegmentCount }
    ) {
        return null
    }

    val assignmentBySegmentIndex = taggingSegmentIndexes.zip(selectedAssignments).toMap()
    return List(originalSegmentCount) { segmentIndex ->
        assignmentBySegmentIndex[segmentIndex] ?: VoiceCallAudioTagAssignment(tagId = null)
    }
}

private fun fallbackVoiceCallResponseForSegments(
    originalSegments: List<String>,
    taggingSegmentIndexes: List<Int>,
    reason: VoiceCallTaggingFallbackReason,
    format: VoiceCallAudioTagFormat,
): ParsedVoiceCallResponse {
    val taggedIndexes = taggingSegmentIndexes.toSet()
    return buildParsedVoiceCallResponse(
        originalSegments.mapIndexed { index, text ->
            val shouldTag = index in taggedIndexes
            ParsedVoiceCallSegment(
                tag = format.fallbackTag().takeIf { shouldTag },
                text = text,
                selectionSource = if (shouldTag) {
                    VoiceCallTagSelectionSource.FALLBACK
                } else {
                    VoiceCallTagSelectionSource.STRUCTURED
                },
                fallbackReason = reason.takeIf { shouldTag },
            )
        },
        format,
    )
}

private fun fallbackVoiceCallResponse(
    text: String,
    reason: VoiceCallTaggingFallbackReason,
    format: VoiceCallAudioTagFormat,
): ParsedVoiceCallResponse {
    return buildParsedVoiceCallResponse(
        listOf(
            ParsedVoiceCallSegment(
                tag = format.fallbackTag(),
                text = text,
                selectionSource = VoiceCallTagSelectionSource.FALLBACK,
                fallbackReason = reason,
            )
        ),
        format,
    )
}

private fun VoiceCallAudioTagFormat.fallbackTag(): VoiceCallAudioTag? {
    return VoiceCallAudioTag.BREATH.takeUnless { allowsNoTag }
}

private fun extractJsonObject(rawResponse: String): String? {
    val candidate = jsonFenceRegex.find(rawResponse)?.groupValues?.getOrNull(1) ?: rawResponse
    val start = candidate.indexOf('{')
    val end = candidate.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return candidate.substring(start, end + 1)
}

private fun extractFallbackVisibleText(rawResponse: String): String {
    val textFields = malformedJsonTextFieldRegex.findAll(rawResponse)
        .mapNotNull { match ->
            match.groupValues.getOrNull(1)?.let { encoded ->
                runCatching {
                    voiceCallResponseJson.decodeFromString<JsonPrimitive>(encoded).content
                }.getOrNull()
            }
        }
        .map { it.removeModelAudioDirections().trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (textFields.isNotEmpty()) return textFields.joinToString("\n")

    return rawResponse
        .replace(jsonFenceRegex) { it.groupValues[1] }
        .removeModelAudioDirections()
        .trim()
}

private fun String.removeModelAudioDirections(): String {
    return replace(bracketedDirectionRegex, "").replace(Regex("""[ \t]{2,}"""), " ")
}

private fun String.stripKnownVoiceCallAudioTags(): String {
    var result = this
    VoiceCallAudioTag.entries.forEach { tag ->
        VoiceCallAudioTagFormat.entries.forEach { format ->
            result = result.replace(
                Regex(
                    Regex.escape(format.render(tag.word)) + """[ \t]*(?:[，,][ \t]*)?""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
        }
    }
    return result.replace(legacyBracketedAudioTagRegex) { match ->
        val direction = match.groupValues[1].lowercase()
        if (legacyAudioDirectionKeywords.any(direction::contains)) "" else match.value
    }
}
