package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import kotlinx.serialization.json.Json

/** Persists autonomous wake plans separately from the normal heartbeat settings. */
class HeartbeatScheduleStore(
    context: Context,
    private val assistantId: String? = null,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun read(): HeartbeatSchedulePlan? {
        val raw = preferences.getString(planKey(), null)
            ?: if (assistantId != null) {
                preferences.getString(PLAN_KEY, null)?.takeIf { legacy ->
                    runCatching {
                        json.decodeFromString<HeartbeatSchedulePlan>(legacy).assistantId == assistantId
                    }.getOrDefault(false)
                }
            } else {
                null
            }
            ?: return null
        return runCatching {
            json.decodeFromString<HeartbeatSchedulePlan>(raw)
        }.getOrNull()
    }

    fun replace(plan: HeartbeatSchedulePlan) {
        val normalized = plan.copy(wakeAtMillis = plan.wakeAtMillis.distinct().sorted())
        preferences.edit()
            .putString(planKey(plan.assistantId), json.encodeToString(normalized))
            .apply()
    }

    fun clear() {
        preferences.edit().remove(planKey()).apply()
    }

    fun readForAssistant(assistantId: String?): HeartbeatSchedulePlan? =
        HeartbeatScheduleStore(appContext, assistantId).read()

    fun nextWakeAtMillis(assistantId: String? = null): Long? = readForAssistant(assistantId)
        ?.wakeAtMillis
        ?.minOrNull()

    /** Consumes due entries and advances a repeating interval or calendar plan. */
    fun consumeDue(
        assistantId: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val plan = readForAssistant(assistantId) ?: return false
        if (plan.wakeAtMillis.none { it <= nowMillis }) return false

        val futureWakeTimes = plan.wakeAtMillis.filter { it > nowMillis }
        val nextWakeTimes = if (futureWakeTimes.isNotEmpty()) {
            futureWakeTimes
        } else {
            when {
                plan.repeatIntervalMinutes != null && plan.repeatIntervalMinutes > 0L -> {
                    listOf(nowMillis + plan.repeatIntervalMinutes * 60_000L)
                }
                plan.recurrence != null && plan.recurrenceTimeMinutes != null -> {
                    listOf(
                        HeartbeatSchedulePlanner.nextCalendarWakeAt(
                            recurrence = plan.recurrence,
                            timeMinutes = plan.recurrenceTimeMinutes,
                            nowMillis = nowMillis,
                        )
                    )
                }
                else -> emptyList()
            }
        }
        if (nextWakeTimes.isEmpty()) {
            HeartbeatScheduleStore(appContext, assistantId).clear()
        } else {
            HeartbeatScheduleStore(appContext, assistantId)
                .replace(plan.copy(wakeAtMillis = nextWakeTimes))
        }
        return true
    }

    companion object {
        private const val PREFERENCES_NAME = "personal_heartbeat_schedule"
        private const val PLAN_KEY = "plan"
    }

    private fun planKey(id: String? = assistantId): String =
        id?.let { "${PLAN_KEY}_$it" } ?: PLAN_KEY
}
