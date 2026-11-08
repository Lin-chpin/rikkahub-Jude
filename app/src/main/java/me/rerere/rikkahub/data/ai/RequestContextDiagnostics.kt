package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.common.android.Logging
import java.security.MessageDigest
import java.util.UUID

internal class RequestContextDiagnostics(
    private val writeLog: (String, String) -> Unit = { tag, message -> Log.i(tag, message); Unit },
) {
    private data class MessageStamp(val fields: Map<String, String>, val hash: String)
    private data class Snapshot(
        val requestId: String,
        val source: String,
        val atMillis: Long,
        val options: Map<String, String>,
        val tools: String,
        val system: String,
        val messages: List<MessageStamp>,
        val history: List<MessageStamp>,
    )
    private class Baselines {
        var latest: Snapshot? = null
        var normal: Snapshot? = null
        var heartbeatRequests: Int = 0
    }
    // Keep only fingerprints: private heartbeat/screen content must never survive in diagnostic state.
    private val conversations = linkedMapOf<String, Baselines>()

    fun begin(conversationId: String, source: String, api: String, providerKey: String) =
        Trace(conversationId, source, api, providerKey.fingerprint())

    inner class Trace internal constructor(
        private val conversationId: String,
        private val source: String,
        private val api: String,
        private val providerHash: String,
    ) {
        private var requestId = "unprepared"

        fun request(body: JsonObject) {
            requestId = UUID.randomUUID().toString()
            record(conversationId, source, api, providerHash, requestId, body)
        }

        fun usage(body: JsonObject) {
            val details = (body["prompt_tokens_details"] ?: body["input_tokens_details"]) as? JsonObject
            fun value(element: JsonElement?) = (element as? JsonPrimitive)?.longOrNull?.toString() ?: "missing"
            writeLog("PromptCacheUsage", "request=$requestId conversation=$conversationId source=$source api=$api " +
                "input=${value(body["prompt_tokens"] ?: body["input_tokens"])} " +
                "cached=${value(details?.get("cached_tokens"))} " +
                "deepseekHit=${value(body["prompt_cache_hit_tokens"])} deepseekMiss=${value(body["prompt_cache_miss_tokens"])} " +
                "cacheWrite=${value(details?.get("cache_write_tokens"))}")
            if (source == "VoiceCall") {
                Logging.log("VoiceCallRequest", "request=$requestId event=provider_usage_received")
            }
        }
    }

    @Synchronized
    private fun record(conversationId: String, source: String, api: String, providerHash: String, requestId: String, body: JsonObject) {
        val items = (body["messages"] ?: body["input"]) as? JsonArray ?: JsonArray(emptyList())
        fun role(item: JsonElement) = ((item as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull
        if (source == "VoiceCall" && api == "ChatCompletions") {
            val requestMessages = items.mapIndexedNotNull { index, item ->
                (item as? JsonObject)?.let { Triple(index, role(item).orEmpty(), it["content"].promptDiagnosticText()) }
            }
            val systemMessages = requestMessages.filter { (_, role, _) -> role in setOf("system", "developer") }
            val runtimeStates = systemMessages.flatMap { (index, role, text) ->
                VOICE_CALL_STATE.findAll(text).map { "${it.groupValues[1]}@$role[$index]" }
            }
            val lastUserIndex = requestMessages.indexOfLast { (_, role, _) -> role == "user" }
            val priorContext = requestMessages.filter { (index, role, _) ->
                index < lastUserIndex && role !in setOf("system", "developer")
            }
            val priorThirdPersonItems = priorContext.count { (_, _, text) ->
                text.contains("第三人称") || text.contains("third person", ignoreCase = true) ||
                    text.contains("third-person", ignoreCase = true)
            }
            val priorAssistantMessages = priorContext.filter { (_, role, _) -> role == "assistant" }
            val priorAssistantThirdPersonStyleItems = priorAssistantMessages.count { (_, _, text) ->
                isThirdPersonDominant(text)
            }
            val priorNovelNarrationItems = priorContext.count { (_, _, text) ->
                text.contains("小说叙事") || text.contains("像小说") ||
                    text.contains("novel narration", ignoreCase = true)
            }
            val compressedSummary = systemMessages.firstNotNullOfOrNull { (_, _, text) ->
                text.substringAfter(COMPRESSED_SUMMARY_MARKER, "").takeIf { it.isNotEmpty() }
            }
            // ponytail: Pronoun counts can misclassify quoted dialogue; inspect captured text only if this signal is inconclusive.
            val summaryThirdPersonDominant = compressedSummary?.let(::isThirdPersonDominant)
            val runtimeInstructionIndex = systemMessages.lastOrNull { (_, _, text) ->
                VOICE_CALL_STATE.containsMatchIn(text)
            }?.first
            val summary = "request=$requestId event=serialized " +
                "runtimeStates=${runtimeStates.ifEmpty { listOf("missing") }.joinToString(",") } " +
                "runtimeAfterLastUser=${lastUserIndex >= 0 && runtimeInstructionIndex != null && runtimeInstructionIndex > lastUserIndex} " +
                "firstPerson=${systemMessages.any { (_, _, text) -> text.contains("Speak in the first person", ignoreCase = true) || text.contains("第1人称") || text.contains("第一人称") }} " +
                "spokenOnly=${systemMessages.any { (_, _, text) -> text.contains("output only words you would actually say", ignoreCase = true) || text.contains("只说口语") }} " +
                "connectionEvent=${systemMessages.any { (_, _, text) -> "[VOICE_CALL_RUNTIME_EVENT]" in text }} " +
                "priorMessages=${priorContext.size} priorAssistantMessages=${priorAssistantMessages.size} " +
                "priorThirdPersonCueItems=$priorThirdPersonItems " +
                "priorAssistantThirdPersonStyleItems=$priorAssistantThirdPersonStyleItems " +
                "priorNovelNarrationItems=$priorNovelNarrationItems " +
                "compressedSummaryPresent=${compressedSummary != null} compressedSummaryThirdPersonDominant=$summaryThirdPersonDominant"
            Logging.log("VoiceCallRequest", summary)
            writeLog("VoiceCallRequest", summary)
        }
        val current = Snapshot(
            requestId, source, System.currentTimeMillis(),
            body.filterKeys { it !in setOf("messages", "input", "tools", "instructions", "stream", "stream_options") }
                .mapValues { it.value.toString().fingerprint() } + mapOf("api" to api.fingerprint(), "provider" to providerHash),
            body["tools"].toString().fingerprint(),
            (body["instructions"].toString() + items.filter { role(it) in setOf("system", "developer") }.joinToString()).fingerprint(),
            items.map(::stamp),
            items.filter { role(it) !in setOf("system", "developer") }.map(::stamp),
        )
        val baselines = conversations.getOrPut(conversationId) { Baselines() }
        val prefix = "request=$requestId conversation=$conversationId source=$source api=$api"
        writeLog("PromptCacheRequest", "$prefix items=${items.size} historyItems=${current.history.size} " +
            "systemHash=${current.system} toolsHash=${current.tools} heartbeatRequestsSinceNormal=${baselines.heartbeatRequests}")
        compare(prefix, "previousRequest", baselines.latest, current)
        // A heartbeat must not overwrite the ordinary-chat baseline needed to diagnose the next user turn.
        if (source == "Normal") {
            compare(prefix, "previousNormal", baselines.normal, current)
            baselines.normal = current
            baselines.heartbeatRequests = 0
        } else if (source == "Heartbeat") {
            baselines.heartbeatRequests++
        }
        baselines.latest = current
        if (conversations.size > 32) conversations.remove(conversations.keys.first())
    }

    private fun compare(prefix: String, label: String, previous: Snapshot?, current: Snapshot) {
        if (previous == null) {
            writeLog("PromptCacheRequest", "$prefix compare=$label baseline=missing")
            return
        }
        val sharedItems = sharedPrefix(previous.messages, current.messages)
        val sharedHistory = sharedPrefix(previous.history, current.history)
        val optionDiff = (previous.options.keys + current.options.keys).firstOrNull {
            previous.options[it] != current.options[it]
        } ?: "none"
        writeLog("PromptCacheRequest", "$prefix compare=$label baseline=${previous.requestId} baselineSource=${previous.source} " +
            "elapsedMs=${current.atMillis - previous.atMillis} sameSystem=${previous.system == current.system} " +
            "sameTools=${previous.tools == current.tools} optionDiff=$optionDiff " +
            "wireSharedItems=$sharedItems previousItems=${previous.messages.size} " +
            "wireFirstDiff=${difference(previous.messages, current.messages, sharedItems)} " +
            "historySharedItems=$sharedHistory previousHistoryItems=${previous.history.size} " +
            "historyFirstDiff=${difference(previous.history, current.history, sharedHistory)}")
    }

    private fun stamp(item: JsonElement) = MessageStamp(
        (item as? JsonObject)?.mapValues { it.value.toString().fingerprint() }
            ?: mapOf("value" to item.toString().fingerprint()),
        item.toString().fingerprint(),
    )

    private fun sharedPrefix(previous: List<MessageStamp>, current: List<MessageStamp>): Int {
        var index = 0
        while (index < minOf(previous.size, current.size) && previous[index].hash == current[index].hash) index++
        return index
    }

    private fun difference(previous: List<MessageStamp>, current: List<MessageStamp>, index: Int): String {
        if (index == previous.size && index == current.size) return "none"
        if (index == previous.size) return "items[$index].appended"
        if (index == current.size) return "items[$index].removed"
        val oldFields = previous[index].fields
        val newFields = current[index].fields
        val field = (oldFields.keys + newFields.keys).firstOrNull { oldFields[it] != newFields[it] } ?: "fieldOrder"
        return "items[$index].$field"
    }

    private companion object {
        val VOICE_CALL_STATE = Regex("\\[VOICE_CALL_RUNTIME_STATE]\\s*state=(ACTIVE|ENDED|INACTIVE)")
        const val COMPRESSED_SUMMARY_MARKER = "The following is a compressed summary of earlier messages in this conversation."
    }
}

private fun JsonElement?.promptDiagnosticText(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString("\n") { it.promptDiagnosticText() }
    is JsonObject -> get("text")?.promptDiagnosticText() ?: ""
    else -> ""
}

private fun isThirdPersonDominant(text: String): Boolean {
    val thirdPerson = text.count { it == '他' || it == '她' || it == '彼' }
    val firstPerson = text.count { it == '我' || it == '僕' || it == '私' || it == '俺' }
    return thirdPerson >= 3 && thirdPerson > firstPerson
}

private fun String.fingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
