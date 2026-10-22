package me.rerere.rikkahub.personal.heartbeat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal data class HeartbeatGoodNightState(val active: Boolean, val noUsageRuns: Int = 0) {
    fun afterHeartbeat(currentRunMessages: List<UIMessage>): HeartbeatGoodNightState {
        if (!active) return this
        val lockedSomething = currentRunMessages.flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>().any { tool ->
                tool.toolName == "usage_lock_control" &&
                    tool.inputAsJson().jsonObject["action"]?.jsonPrimitive?.contentOrNull == "lock" &&
                    tool.output.filterIsInstance<UIMessagePart.Text>().any { output ->
                        runCatching {
                            Json.parseToJsonElement(output.text).jsonObject["success"]
                                ?.jsonPrimitive?.booleanOrNull == true
                        }.getOrDefault(false)
                    }
            }
        val runs = if (lockedSomething) 0 else noUsageRuns + 1
        return if (runs >= 3) HeartbeatGoodNightState(active = false) else copy(noUsageRuns = runs)
    }
}
