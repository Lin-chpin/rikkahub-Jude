package me.rerere.rikkahub.data.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
            segment.tag?.let { "${format.render(it.word)} ${segment.text}" } ?: segment.text
        },
        displayText = segments.joinToString("\n") { segment ->
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
            label?.let { "${format.render(it)} ${segment.text}" } ?: segment.text
        },
        segments = segments,
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
    val originalText = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (originalText.isBlank()) return this

    val originalSegments = splitVoiceCallAudioTaggingSegments(originalText)
    val acceptedTagging = if (selectionFailureReason != null) {
        fallbackVoiceCallResponse(originalText, selectionFailureReason, format)
    } else {
        buildVoiceCallResponseFromSelectedTagIds(
            originalSegments = originalSegments,
            selectedTagIds = selectedTagIds,
            format = format,
        ) ?: fallbackVoiceCallResponse(
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

internal fun splitVoiceCallAudioTaggingSegments(text: String): List<String> {
    val segments = mutableListOf<String>()
    var start = 0
    var index = 0
    while (index < text.length) {
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

private fun buildVoiceCallResponseFromSelectedTagIds(
    originalSegments: List<String>,
    selectedTagIds: List<String?>?,
    format: VoiceCallAudioTagFormat,
): ParsedVoiceCallResponse? {
    if (selectedTagIds == null || selectedTagIds.size != originalSegments.size) return null

    return buildParsedVoiceCallResponse(
        originalSegments.mapIndexed { index, text ->
            val selectedTag = selectedTagIds[index]?.let { VoiceCallAudioTag.fromId(it) ?: return null }
            ParsedVoiceCallSegment(
                tag = selectedTag,
                text = text,
                selectionSource = VoiceCallTagSelectionSource.STRUCTURED,
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
                    Regex.escape(format.render(tag.word)) + """[ \t]*""",
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
