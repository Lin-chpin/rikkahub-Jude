package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Adapter that exposes autonomous wake planning to normal chat conversations. */
class HeartbeatScheduleTool(
    private val context: Context,
    private val assistantId: Uuid?,
) {
    val tool: Tool = Tool(
        name = NAME,
        description = """
            Control when the private heartbeat wakes up. This tool is available in normal chat only.
            Use action=set to replace the current plan, action=status to inspect it, or action=cancel to clear it.
            Set trigger_type=once for a one-time plan. For mode=at provide an ISO timestamp in at. For
            mode=interval provide interval_minutes; count creates that many one-time future wakes. For
            trigger_type=recurring and mode=interval, the heartbeat repeats at that interval. For
            trigger_type=recurring and mode=calendar, use recurrence=daily or recurrence=weekdays and a local
            time such as 21:00. This supports schedules such as every night or every workday.
            For mode=random_window provide window_start, window_end, and count; the app chooses that many random
            future wake times inside the window. Existing callers may use repeat=true for interval repetition.
            The schedule is autonomous and is not limited by the normal heartbeat min/max interval settings.
            A relative or one-time wake fires at its own due time; after it fires, the normal heartbeat interval
            is recalculated from that trigger time, not from the end of the API call. A recurring calendar wake
            such as daily at 04:00 remains anchored to that clock time and fires even when the app is already open.
            The heartbeat master switch still controls whether background execution is enabled.
        """.trimIndent().replace("\n", " "),
        needsApproval = false,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("set")
                            add("status")
                            add("cancel")
                        })
                    })
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(HeartbeatSchedulePlanner.MODE_AT)
                            add(HeartbeatSchedulePlanner.MODE_INTERVAL)
                            add(HeartbeatSchedulePlanner.MODE_RANDOM_WINDOW)
                            add(HeartbeatSchedulePlanner.MODE_CALENDAR)
                        })
                    })
                    put("trigger_type", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(HeartbeatSchedulePlanner.TRIGGER_ONCE)
                            add(HeartbeatSchedulePlanner.TRIGGER_RECURRING)
                        })
                        put("description", "Whether this is a one-time or recurring schedule")
                    })
                    put("at", buildJsonObject {
                        put("type", "string")
                        put("description", "Future ISO-8601 timestamp for mode=at")
                    })
                    put("window_start", buildJsonObject {
                        put("type", "string")
                        put("description", "ISO-8601 start timestamp for mode=random_window")
                    })
                    put("window_end", buildJsonObject {
                        put("type", "string")
                        put("description", "ISO-8601 end timestamp for mode=random_window")
                    })
                    put("interval_minutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "Positive interval in minutes for mode=interval")
                    })
                    put("count", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of future wakes; required for random_window")
                    })
                    put("repeat", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Legacy alias for trigger_type=recurring with mode=interval")
                    })
                    put("recurrence", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(HeartbeatSchedulePlanner.RECURRENCE_DAILY)
                            add(HeartbeatSchedulePlanner.RECURRENCE_WEEKDAYS)
                        })
                        put("description", "Calendar recurrence for mode=calendar")
                    })
                    put("time", buildJsonObject {
                        put("type", "string")
                        put("description", "Local time in HH:mm format for mode=calendar")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { arguments -> execute(arguments.jsonObject) },
    )

    private fun execute(arguments: kotlinx.serialization.json.JsonObject): List<UIMessagePart> {
        return when (arguments["action"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "set" -> setPlan(arguments)
            "status" -> listOf(resultPayload(statusPayload()))
            "cancel" -> {
                val scheduleStore = HeartbeatScheduleStore(context, assistantId?.toString())
                if (scheduleStore.read() != null) {
                    scheduleStore.clear()
                }
                HeartbeatScheduler.sync(context)
                listOf(resultPayload(buildJsonObject {
                    put("success", true)
                    put("cancelled", true)
                }))
            }
            else -> error("action must be set, status, or cancel")
        }
    }

    private fun setPlan(arguments: kotlinx.serialization.json.JsonObject): List<UIMessagePart> {
        val legacyRepeat = arguments["repeat"]?.jsonPrimitive?.booleanOrNull == true
        val request = HeartbeatScheduleRequest(
            mode = arguments["mode"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            at = arguments["at"]?.jsonPrimitive?.contentOrNull,
            windowStart = arguments["window_start"]?.jsonPrimitive?.contentOrNull,
            windowEnd = arguments["window_end"]?.jsonPrimitive?.contentOrNull,
            intervalMinutes = arguments["interval_minutes"]?.jsonPrimitive?.longOrNull,
            count = arguments["count"]?.jsonPrimitive?.intOrNull,
            repeat = legacyRepeat,
            triggerType = arguments["trigger_type"]?.jsonPrimitive?.contentOrNull
                ?: if (legacyRepeat) HeartbeatSchedulePlanner.TRIGGER_RECURRING
                else HeartbeatSchedulePlanner.TRIGGER_ONCE,
            recurrence = arguments["recurrence"]?.jsonPrimitive?.contentOrNull,
            time = arguments["time"]?.jsonPrimitive?.contentOrNull,
        )
        val plan = HeartbeatSchedulePlanner().create(
            request = request,
            assistantId = assistantId?.toString(),
        )
        HeartbeatScheduleStore(context).replace(plan)
        HeartbeatScheduler.sync(context)
        val payload = buildJsonObject {
            statusPayload().forEach { (key, value) -> put(key, value) }
            put("success", true)
            put("replaced", true)
            put(
                "trigger_type",
                if (plan.repeatIntervalMinutes != null || plan.recurrence != null) {
                    HeartbeatSchedulePlanner.TRIGGER_RECURRING
                } else {
                    HeartbeatSchedulePlanner.TRIGGER_ONCE
                },
            )
            plan.recurrence?.let { put("recurrence", it) }
            plan.recurrenceTimeMinutes?.let { put("time_minutes", it) }
        }
        return listOf(resultPayload(payload))
    }

    private fun statusPayload() = buildJsonObject {
        val plan = HeartbeatScheduleStore(context).readForAssistant(assistantId?.toString())
        val configStore = HeartbeatConfigStore(context)
        val enabled = assistantId?.let { configStore.readForAssistant(it.toString()).enabled } == true
        configStore.close()
        put("heartbeat_enabled", enabled)
        put("scheduled", plan != null)
        if (plan != null) {
            put(
                "trigger_type",
                if (plan.repeatIntervalMinutes != null || plan.recurrence != null) {
                    HeartbeatSchedulePlanner.TRIGGER_RECURRING
                } else {
                    HeartbeatSchedulePlanner.TRIGGER_ONCE
                },
            )
            put("assistant_id", plan.assistantId)
            put("next_wake_at_ms", plan.wakeAtMillis.minOrNull() ?: 0L)
            put("wake_times_ms", buildJsonArray {
                plan.wakeAtMillis.forEach(::add)
            })
            plan.repeatIntervalMinutes?.let { put("repeat_interval_minutes", it) }
            plan.recurrence?.let { put("recurrence", it) }
            plan.recurrenceTimeMinutes?.let { put("time_minutes", it) }
        }
    }

    private fun resultPayload(payload: kotlinx.serialization.json.JsonObject): UIMessagePart.Text =
        UIMessagePart.Text(payload.toString())

    companion object {
        const val NAME = "heartbeat_schedule"
    }
}
