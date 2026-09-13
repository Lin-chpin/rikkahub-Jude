package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.SYSTEM_PROMPT_DYNAMIC_SECTION
import me.rerere.ai.ui.SYSTEM_PROMPT_SECTION_METADATA_KEY
import me.rerere.ai.ui.SYSTEM_PROMPT_STABLE_SECTION
import me.rerere.ai.ui.UIMessagePart

internal data class ResponsePromptPayload(
    val instructions: String?,
    val input: JsonArray,
)

internal fun buildResponsePrompt(
    systemParts: List<UIMessagePart.Text>,
    messageItems: JsonArray,
): ResponsePromptPayload {
    val (stableInstructions, dynamicInstructions) = splitResponseSystemPrompt(systemParts)

    return ResponsePromptPayload(
        instructions = stableInstructions.takeIf { it.isNotBlank() },
        input = buildJsonArray {
            messageItems.forEach(::add)
            dynamicInstructions?.trim()?.takeIf { it.isNotBlank() }?.let(::addDeveloperInstruction)
        },
    )
}

private fun JsonArrayBuilder.addDeveloperInstruction(text: String) {
    add(buildJsonObject {
        put("role", "developer")
        put("content", text)
    })
}

private const val COMPRESSED_SUMMARY_MARKER =
    "The following is a compressed summary of earlier messages in this conversation."

private fun splitResponseSystemPrompt(parts: List<UIMessagePart.Text>): Pair<String, String?> {
    val annotatedParts = parts.filter { it.systemPromptSection() != null }
    if (annotatedParts.isNotEmpty()) {
        return annotatedParts
            .filter { it.systemPromptSection() == SYSTEM_PROMPT_STABLE_SECTION }
            .joinToString("\n") { it.text }
            .trim() to annotatedParts
            .filter { it.systemPromptSection() == SYSTEM_PROMPT_DYNAMIC_SECTION }
            .joinToString("\n") { it.text }
            .trim()
            .takeIf { it.isNotBlank() }
    }

    val prompt = parts.joinToString("\n") { it.text }
    val markerIndex = prompt.indexOf(COMPRESSED_SUMMARY_MARKER)
    if (markerIndex < 0) return prompt.trim() to null

    return prompt.substring(0, markerIndex).trim() to prompt.substring(markerIndex).trim()
}

private fun UIMessagePart.Text.systemPromptSection(): String? =
    metadata?.get(SYSTEM_PROMPT_SECTION_METADATA_KEY)?.jsonPrimitive?.contentOrNull
