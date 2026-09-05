package me.rerere.rikkahub.personal.heartbeat

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

data class HeartbeatScheduleRequest(
    val mode: String,
    val at: String? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val intervalMinutes: Long? = null,
    val count: Int? = null,
    val repeat: Boolean = false,
    val triggerType: String = HeartbeatSchedulePlanner.TRIGGER_ONCE,
    val recurrence: String? = null,
    val time: String? = null,
)

/** Converts model parameters into concrete future wake times. */
class HeartbeatSchedulePlanner(
    private val random: Random = Random.Default,
) {
    fun create(
        request: HeartbeatScheduleRequest,
        assistantId: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): HeartbeatSchedulePlan {
        val repeating = request.triggerType == TRIGGER_RECURRING || request.repeat
        require(request.triggerType in setOf(TRIGGER_ONCE, TRIGGER_RECURRING)) {
            "trigger_type must be once or recurring"
        }
        val calendarTimeMinutes = if (request.mode == MODE_CALENDAR) {
            parseLocalTime(request.time).toSecondOfDay() / 60
        } else {
            null
        }
        val wakeTimes = when (request.mode) {
            MODE_AT -> {
                require(!repeating) { "recurring schedules need mode=interval or mode=calendar" }
                listOf(requireFuture(parseTime(request.at), nowMillis))
            }
            MODE_INTERVAL -> createIntervalTimes(request, nowMillis, repeating)
            MODE_RANDOM_WINDOW -> {
                require(!repeating) { "random_window is only for one-time schedules" }
                createRandomTimes(request, nowMillis)
            }
            MODE_CALENDAR -> {
                require(repeating) { "calendar schedules require trigger_type=recurring" }
                listOf(
                    nextCalendarWakeAt(
                        recurrence = request.recurrence,
                        timeMinutes = requireNotNull(calendarTimeMinutes),
                        nowMillis = nowMillis,
                    )
                )
            }
            else -> error("mode must be at, interval, random_window, or calendar")
        }
        val repeatInterval = if (request.mode == MODE_INTERVAL && repeating) {
            requireInterval(request.intervalMinutes)
        } else {
            null
        }
        val recurrence = if (request.mode == MODE_CALENDAR) request.recurrence else null
        return HeartbeatSchedulePlan(
            assistantId = assistantId,
            wakeAtMillis = wakeTimes,
            repeatIntervalMinutes = repeatInterval,
            recurrence = recurrence,
            recurrenceTimeMinutes = calendarTimeMinutes,
            createdAtMillis = nowMillis,
        )
    }

    private fun createIntervalTimes(
        request: HeartbeatScheduleRequest,
        nowMillis: Long,
        repeating: Boolean,
    ): List<Long> {
        val interval = requireInterval(request.intervalMinutes)
        if (repeating) return listOf(nowMillis + interval * 60_000L)
        val count = request.count ?: 1
        require(count > 0) { "count must be greater than zero" }
        return (1..count).map { occurrence ->
            nowMillis + interval * occurrence * 60_000L
        }
    }

    private fun createRandomTimes(
        request: HeartbeatScheduleRequest,
        nowMillis: Long,
    ): List<Long> {
        val start = parseTime(request.windowStart)
        val end = parseTime(request.windowEnd)
        val effectiveStart = maxOf(start, nowMillis + 1_000L)
        require(end > effectiveStart) { "window_end must be after the current time and window_start" }
        val count = request.count ?: error("count is required for random_window")
        require(count > 0) { "count must be greater than zero" }
        require(end - effectiveStart >= count) { "random window is too small for count" }
        return buildSet {
            while (size < count) {
                add(random.nextLong(effectiveStart, end))
            }
        }.sorted()
    }

    private fun requireInterval(value: Long?): Long = requireNotNull(value)
        .also { require(it > 0L) { "interval_minutes must be greater than zero" } }

    private fun requireFuture(value: Long, nowMillis: Long): Long {
        require(value > nowMillis) { "at must be in the future" }
        return value
    }

    private fun parseTime(value: String?): Long {
        val input = value?.trim().takeIf { !it.isNullOrEmpty() }
            ?: error("a time value is required")
        return runCatching { Instant.parse(input).toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(input).toInstant().toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(input)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
            .getOrElse { error("invalid time: $input") }
    }

    private fun parseLocalTime(value: String?): LocalTime {
        val input = value?.trim().takeIf { !it.isNullOrEmpty() }
            ?: error("time is required in HH:mm format")
        val parts = input.split(':')
        require(parts.size == 2) { "time must use HH:mm format" }
        val hour = parts[0].toIntOrNull()
            ?: error("time must use HH:mm format")
        val minute = parts[1].toIntOrNull()
            ?: error("time must use HH:mm format")
        require(hour in 0..23 && minute in 0..59) { "time must use HH:mm format" }
        return LocalTime.of(hour, minute)
    }

    companion object {
        const val MODE_AT = "at"
        const val MODE_INTERVAL = "interval"
        const val MODE_RANDOM_WINDOW = "random_window"
        const val MODE_CALENDAR = "calendar"
        const val TRIGGER_ONCE = "once"
        const val TRIGGER_RECURRING = "recurring"
        const val RECURRENCE_DAILY = "daily"
        const val RECURRENCE_WEEKDAYS = "weekdays"

        fun nextCalendarWakeAt(
            recurrence: String?,
            timeMinutes: Int,
            nowMillis: Long,
        ): Long {
            require(recurrence == RECURRENCE_DAILY || recurrence == RECURRENCE_WEEKDAYS) {
                "recurrence must be daily or weekdays"
            }
            require(timeMinutes in 0..(24 * 60 - 1)) {
                "time must be between 00:00 and 23:59"
            }
            val localTime = LocalTime.of(timeMinutes / 60, timeMinutes % 60)
            val zone = ZoneId.systemDefault()
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
            var date = now.toLocalDate()
            repeat(8) {
                val isAllowedDay = recurrence == RECURRENCE_DAILY || date.dayOfWeek.value in 1..5
                if (isAllowedDay) {
                    val candidate = ZonedDateTime.of(date, localTime, zone)
                        .toInstant()
                        .toEpochMilli()
                    if (candidate > nowMillis) return candidate
                }
                date = date.plusDays(1)
            }
            error("could not find the next calendar wake time")
        }
    }
}
