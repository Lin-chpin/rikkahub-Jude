package me.rerere.rikkahub.personal.heartbeat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import me.rerere.common.android.Logging

object HeartbeatScheduler {
    private const val REQUEST_CODE = 47021
    private const val LOG_TAG = "Heartbeat"
    private const val GOOD_NIGHT_INTERVAL_MINUTES = 10L
    private const val MIN_SCHEDULE_LEAD_MILLIS = 1_000L

    /** Keeps one AlarmManager alarm armed for the earliest enabled assistant. */
    fun sync(context: Context) {
        val rootStore = HeartbeatConfigStore(context)
        val configs = rootStore.readAllConfigs().filter { it.enabled && it.assistantId != null }
        rootStore.close()
        if (configs.isEmpty()) {
            cancel(context)
            return
        }
        val now = System.currentTimeMillis()
        configs.forEach { config ->
            val store = HeartbeatConfigStore(context, config.assistantId)
            val triggerAt = store.readNextTriggerAt()
            store.close()
            val autonomousWakeAt = HeartbeatScheduleStore(context)
                .nextWakeAtMillis(config.assistantId!!)
            val storedAutonomousTrigger = autonomousWakeAt != null && triggerAt == autonomousWakeAt
            val autonomousTriggerEarlier = autonomousWakeAt != null &&
                (triggerAt == null || autonomousWakeAt < triggerAt)
            val triggerTooSoon = triggerAt != null &&
                !storedAutonomousTrigger &&
                triggerAt < now + config.minIntervalMinutes * 60_000L
            if (
                triggerAt == null ||
                triggerAt <= now ||
                storedAutonomousTrigger ||
                autonomousTriggerEarlier ||
                triggerTooSoon
            ) {
                scheduleNext(context, config)
            }
        }
        rearmEarliest(context)
    }

    fun scheduleNext(
        context: Context,
        rawConfig: HeartbeatConfig,
        intervalAnchorAtMillis: Long? = null,
    ) {
        val config = rawConfig.normalized()
        val assistantId = config.assistantId
        if (!config.enabled || assistantId.isNullOrBlank()) {
            cancel(context, assistantId)
            return
        }

        val store = HeartbeatConfigStore(context, assistantId)
        val goodNightActive = store.isGoodNightActive()
        val lastAssistantMessageAt = store.lastAssistantMessageAt()
        val now = System.currentTimeMillis()
        val desireState = store.readDesireState().advance(now)
        val retryAtMillis = store.readDiagnostics().nextRetryAtMillis
        store.recordDesireState(desireState)
        store.close()
        val autonomousWakeAt = HeartbeatScheduleStore(context)
            .nextWakeAtMillis(assistantId)
        val delayMinutes = if (goodNightActive) {
            GOOD_NIGHT_INTERVAL_MINUTES
        } else {
            val baseDelayMinutes = desireState.nextWakeMinutes(
                minimum = config.minIntervalMinutes,
                maximum = config.maxIntervalMinutes,
            )
            HeartbeatScheduleTiming.jitteredDelayMinutes(
                baseMinutes = baseDelayMinutes,
                minimumMinutes = config.minIntervalMinutes,
                maximumMinutes = config.maxIntervalMinutes,
            )
        }
        val regularTriggerAt = if (retryAtMillis != null && retryAtMillis > now) {
            retryAtMillis
        } else {
            HeartbeatScheduleTiming.nextRegularTriggerAtMillis(
                nowMillis = now,
                delayMinutes = delayMinutes,
                anchorAtMillis = intervalAnchorAtMillis
                    ?: lastAssistantMessageAt.takeIf { it > 0L },
                preserveAnchor = intervalAnchorAtMillis != null,
                minimumLeadMillis = MIN_SCHEDULE_LEAD_MILLIS,
            )
        }
        val autonomousTriggerAt = autonomousWakeAt?.let {
            maxOf(it, now + MIN_SCHEDULE_LEAD_MILLIS)
        }
        val triggerAt = listOfNotNull(regularTriggerAt, autonomousTriggerAt).minOrNull()
            ?: regularTriggerAt
        scheduleAt(context, triggerAt, assistantId)
    }

    fun cancel(context: Context, assistantId: String? = null) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val existing = alarmPendingIntent(context, PendingIntent.FLAG_NO_CREATE)
        if (existing != null && assistantId == null) alarmManager.cancel(existing)

        if (assistantId == null) {
            val rootStore = HeartbeatConfigStore(context)
            rootStore.readAllConfigs().forEach { config ->
                HeartbeatConfigStore(context, config.assistantId).run {
                    recordNextTriggerAt(null)
                    clearPendingRetry()
                    close()
                }
            }
            rootStore.close()
        } else {
            HeartbeatConfigStore(context, assistantId).run {
                recordNextTriggerAt(null)
                clearPendingRetry()
                close()
            }
            rearmEarliest(context)
        }
        Logging.log(LOG_TAG, "schedule=cancelled assistant=$assistantId")
    }

    fun triggerNow(context: Context, assistantId: String?) {
        startHeartbeatService(context, true, "manual", false, assistantId)
    }

    fun triggerReadOnlyTest(context: Context, assistantId: String?) {
        startHeartbeatService(context, true, "read_only_test", true, assistantId)
    }

    fun onAlarmReceived(context: Context, intent: Intent?) {
        val assistantId = intent?.getStringExtra(HeartbeatForegroundService.EXTRA_ASSISTANT_ID)
            ?: dueAssistantId(context)
        startHeartbeatService(context, false, "alarm", false, assistantId)
    }

    fun triggerDue(context: Context, assistantId: String?) {
        startHeartbeatService(context, false, "in_process", false, assistantId)
    }

    fun consumePendingTrigger(context: Context, assistantId: String?) {
        if (assistantId == null) return
        HeartbeatConfigStore(context, assistantId).run {
            recordNextTriggerAt(null)
            close()
        }
        rearmEarliest(context)
    }

    private fun scheduleAt(context: Context, triggerAt: Long, assistantId: String) {
        val store = HeartbeatConfigStore(context, assistantId)
        val previousTriggerAt = store.readNextTriggerAt()
        store.recordNextTriggerAt(triggerAt)
        store.close()
        rearmEarliest(context)
        if (previousTriggerAt != triggerAt) {
            Logging.log(LOG_TAG, "schedule=armed assistant=$assistantId triggerAt=$triggerAt")
        }
    }

    private fun rearmEarliest(context: Context) {
        val rootStore = HeartbeatConfigStore(context)
        val earliest = rootStore.readAllConfigs()
            .filter { it.enabled && it.assistantId != null }
            .mapNotNull { config ->
                HeartbeatConfigStore(context, config.assistantId).run {
                    readNextTriggerAt()?.let { triggerAt -> config.assistantId!! to triggerAt }
                        .also { close() }
                }
            }
            .minByOrNull { it.second }
        rootStore.close()

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val existing = alarmPendingIntent(context, PendingIntent.FLAG_NO_CREATE)
        if (earliest == null) {
            if (existing != null) alarmManager.cancel(existing)
            return
        }
        val (assistantId, triggerAt) = earliest
        val pendingIntent = requireNotNull(alarmPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT, assistantId))
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        Logging.log(LOG_TAG, "schedule=rearmed assistant=$assistantId triggerAt=$triggerAt exact=$exact")
    }

    private fun dueAssistantId(context: Context): String? {
        val now = System.currentTimeMillis()
        val rootStore = HeartbeatConfigStore(context)
        val assistantId = rootStore.readAllConfigs()
            .filter { it.enabled && it.assistantId != null }
            .mapNotNull { config ->
                HeartbeatConfigStore(context, config.assistantId).run {
                    config.assistantId.takeIf { readNextTriggerAt()?.let { it <= now } == true }
                        .also { close() }
                }
            }
            .firstOrNull()
        rootStore.close()
        return assistantId
    }

    private fun startHeartbeatService(
        context: Context,
        forceTrigger: Boolean,
        source: String,
        readOnlyTest: Boolean,
        assistantId: String?,
    ) {
        val intent = Intent(context, HeartbeatForegroundService::class.java)
            .putExtra(HeartbeatForegroundService.EXTRA_TRIGGER, true)
            .putExtra(HeartbeatForegroundService.EXTRA_FORCE_TRIGGER, forceTrigger)
            .putExtra(HeartbeatForegroundService.EXTRA_TRIGGER_SOURCE, source)
            .putExtra(HeartbeatForegroundService.EXTRA_READ_ONLY_TEST, readOnlyTest)
        val rootStore = HeartbeatConfigStore(context)
        val config = assistantId?.let { rootStore.readForAssistant(it) } ?: rootStore.read()
        intent.putExtra(HeartbeatForegroundService.EXTRA_ASSISTANT_ID, config.assistantId)
        val store = HeartbeatConfigStore(context, config.assistantId)
        store.recordRunStatus(
            phase = HeartbeatRunPhase.QUEUED,
            reason = if (readOnlyTest) HeartbeatRunReason.READ_ONLY_TEST else HeartbeatRunReason.NONE,
            assistantId = config.assistantId,
            triggerSource = source,
        )
        try {
            context.startForegroundService(intent)
        } catch (error: Exception) {
            val detail = error.toHeartbeatDiagnosticDetail(stage = "startForegroundService")
            store.recordRunStatus(
                phase = HeartbeatRunPhase.FAILED,
                detail = detail,
                reason = HeartbeatRunReason.SERVICE_START_FAILURE,
                triggerSource = source,
                completionImpact = if (readOnlyTest) HeartbeatRunImpact.NEUTRAL else HeartbeatRunImpact.FAILURE,
            )
            Logging.log(LOG_TAG, "service start failed $detail")
            store.close()
            rootStore.close()
            if (!readOnlyTest && config.enabled) scheduleNext(context, config)
            return
        }
        store.close()
        rootStore.close()
    }

    private fun alarmPendingIntent(
        context: Context,
        flag: Int,
        assistantId: String? = null,
    ): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, HeartbeatAlarmReceiver::class.java).apply {
            assistantId?.let { putExtra(HeartbeatForegroundService.EXTRA_ASSISTANT_ID, it) }
        },
        flag or PendingIntent.FLAG_IMMUTABLE,
    )
}

class HeartbeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        HeartbeatScheduler.onAlarmReceived(context, intent)
    }
}

class HeartbeatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        HeartbeatScheduler.sync(context)
    }
}
