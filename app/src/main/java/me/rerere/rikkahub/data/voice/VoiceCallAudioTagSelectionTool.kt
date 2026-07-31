package me.rerere.rikkahub.data.voice

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal const val VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME =
    "select_voice_call_audio_tags"

internal sealed interface VoiceCallAudioTagSelectionResult {
    data class Selected(
        val assignments: List<VoiceCallAudioTagAssignment>,
    ) : VoiceCallAudioTagSelectionResult

    data object InvalidArguments : VoiceCallAudioTagSelectionResult
}

internal data class VoiceCallAudioTagAssignment(
    val tagId: String?,
    val replacementText: String? = null,
)

/**
 * Creates the only model-facing path that can select live-call audio tags.
 *
 * The schema owns the finite provider-neutral vocabulary and the client
 * validates every index again. The tool accepts no speech text, so the
 * second-pass model cannot replace or rewrite the primary reply.
 */
internal fun createVoiceCallAudioTagSelectionTool(
    segmentCount: Int,
    format: VoiceCallAudioTagFormat,
    onResult: (VoiceCallAudioTagSelectionResult) -> Unit,
): Tool {
    require(segmentCount > 0)
    val allowsLeadingInterjectionReplacement =
        format == VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8

    return Tool(
        name = VOICE_CALL_AUDIO_TAG_SELECTION_TOOL_NAME,
        description = "Select one allowed live-call audio direction for every indexed segment.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "assignments",
                        buildJsonObject {
                            put("type", "array")
                            put("minItems", segmentCount)
                            put("maxItems", segmentCount)
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put(
                                                "segmentIndex",
                                                buildJsonObject {
                                                    put("type", "integer")
                                                    put("minimum", 0)
                                                    put("maximum", segmentCount - 1)
                                                }
                                            )
                                            put(
                                                "tagId",
                                                buildJsonObject {
                                                    put("type", "string")
                                                    put(
                                                        "enum",
                                                        buildJsonArray {
                                                            VoiceCallAudioTag.entries.forEach { tag ->
                                                                add(JsonPrimitive(tag.id))
                                                            }
                                                            if (format.allowsNoTag) {
                                                                add(JsonPrimitive(NO_VOICE_CALL_AUDIO_TAG_ID))
                                                            }
                                                        }
                                                    )
                                                }
                                            )
                                            if (allowsLeadingInterjectionReplacement) {
                                                put(
                                                    "replacementText",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                        put(
                                                            "description",
                                                            "Exact leading spoken interjection to remove from the speech copy; use an empty string when none.",
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    )
                                    put(
                                        "required",
                                        buildJsonArray {
                                            add(JsonPrimitive("segmentIndex"))
                                            add(JsonPrimitive("tagId"))
                                            if (allowsLeadingInterjectionReplacement) {
                                                add(JsonPrimitive("replacementText"))
                                            }
                                        }
                                    )
                                    put("additionalProperties", false)
                                }
                            )
                        }
                    )
                },
                required = listOf("assignments"),
            )
        },
        needsApproval = false,
        execute = { arguments ->
            val selectedAssignments = validateVoiceCallAudioTagAssignmentsWithReplacements(
                arguments = arguments,
                segmentCount = segmentCount,
                format = format,
            )
            if (selectedAssignments == null) {
                onResult(VoiceCallAudioTagSelectionResult.InvalidArguments)
                listOf(UIMessagePart.Text("""{"accepted":false}"""))
            } else {
                onResult(VoiceCallAudioTagSelectionResult.Selected(selectedAssignments))
                listOf(UIMessagePart.Text("""{"accepted":true}"""))
            }
        },
    )
}

internal fun validateVoiceCallAudioTagAssignments(
    arguments: JsonElement,
    segmentCount: Int,
    format: VoiceCallAudioTagFormat,
): List<String?>? {
    return validateVoiceCallAudioTagAssignmentsWithReplacements(
        arguments = arguments,
        segmentCount = segmentCount,
        format = format,
    )?.map(VoiceCallAudioTagAssignment::tagId)
}

internal fun validateVoiceCallAudioTagAssignmentsWithReplacements(
    arguments: JsonElement,
    segmentCount: Int,
    format: VoiceCallAudioTagFormat,
): List<VoiceCallAudioTagAssignment>? {
    if (segmentCount <= 0) return null
    val rootObject = runCatching { arguments.jsonObject }.getOrNull() ?: return null
    if (rootObject.keys != setOf("assignments")) return null
    val assignments = runCatching { rootObject["assignments"]?.jsonArray }.getOrNull() ?: return null
    if (assignments.size != segmentCount) return null
    val allowsLeadingInterjectionReplacement =
        format == VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8

    val assignedIndices = BooleanArray(segmentCount)
    val assignmentsByIndex = arrayOfNulls<VoiceCallAudioTagAssignment>(segmentCount)
    assignments.forEach { assignment ->
        val assignmentObject: JsonObject = runCatching { assignment.jsonObject }.getOrNull()
            ?: return null
        val expectedKeys = if (allowsLeadingInterjectionReplacement) {
            setOf("segmentIndex", "tagId", "replacementText")
        } else {
            setOf("segmentIndex", "tagId")
        }
        if (assignmentObject.keys != expectedKeys) return null
        val segmentIndex = assignmentObject["segmentIndex"]
            ?.jsonPrimitive
            ?.intOrNull
            ?: return null
        val tagId = assignmentObject["tagId"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null
        if (segmentIndex !in assignmentsByIndex.indices || assignedIndices[segmentIndex]) return null

        val replacementText = if (allowsLeadingInterjectionReplacement) {
            assignmentObject["replacementText"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.length <= 120 && !it.contains('\n') && !it.contains('\r') }
                ?: return null
        } else {
            null
        }

        val acceptedTagId = when {
            format.allowsNoTag && tagId == NO_VOICE_CALL_AUDIO_TAG_ID -> null
            else -> VoiceCallAudioTag.fromId(tagId)
                ?.takeIf { tag -> tag.id == tagId }
                ?.id
                ?: return null
        }
        assignedIndices[segmentIndex] = true
        assignmentsByIndex[segmentIndex] = VoiceCallAudioTagAssignment(
            tagId = acceptedTagId,
            replacementText = replacementText?.takeIf { it.isNotBlank() },
        )
    }

    if (!assignedIndices.all { it }) return null
    return assignmentsByIndex.map { it ?: return null }
}
