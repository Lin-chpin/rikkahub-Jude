package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
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
        }
    }

    @Synchronized
    private fun record(conversationId: String, source: String, api: String, providerHash: String, requestId: String, body: JsonObject) {
        val items = (body["messages"] ?: body["input"]) as? JsonArray ?: JsonArray(emptyList())
        fun role(item: JsonElement) = ((item as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull
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
}

private fun String.fingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
