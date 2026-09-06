package me.rerere.rikkahub.personal.heartbeat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.common.android.Logging
import me.rerere.rikkahub.service.VoiceCallSessionRegistry
import org.koin.core.component.KoinComponent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class HeartbeatForegroundService : Service(), KoinComponent {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var executionJob: Job? = null
    private var schedulerJob: Job? = null
    @Volatile
    private var destroyed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            HeartbeatNotifications.createChannel(this)
            startForeground(
                HeartbeatNotifications.RUNNING_NOTIFICATION_ID,
                HeartbeatNotifications.serviceNotification(this, generating = false),
            )
        } catch (error: Exception) {
            val detail = error.toHeartbeatDiagnosticDetail(stage = "startForeground")
            val source = intent?.getStringExtra(EXTRA_TRIGGER_SOURCE) ?: "service_start"
            val readOnlyTest = intent?.getBooleanExtra(EXTRA_READ_ONLY_TEST, false) == true
            val targetAssistantId = intent?.getStringExtra(EXTRA_ASSISTANT_ID)
            val store = HeartbeatConfigStore(this, targetAssistantId)
            val config = store.read()
            store.recordRunStatus(
                phase = HeartbeatRunPhase.FAILED,
                detail = detail,
                reason = HeartbeatRunReason.SERVICE_START_FAILURE,
                triggerSource = source,
                completionImpact = if (readOnlyTest) {
                    HeartbeatRunImpact.NEUTRAL
                } else {
                    HeartbeatRunImpact.FAILURE
                },
            )
            store.close()
            Logging.log(TAG, "foreground promotion failed $detail")
            if (!readOnlyTest && config.enabled) {
                HeartbeatScheduler.scheduleNext(this, config)
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val triggerRequested = intent?.getBooleanExtra(EXTRA_TRIGGER, false) == true
        val forceTrigger = intent?.getBooleanExtra(EXTRA_FORCE_TRIGGER, false) == true
        val readOnlyTest = intent?.getBooleanExtra(EXTRA_READ_ONLY_TEST, false) == true
        val recoverQueuedRun = HeartbeatConfigStore(this).run {
            val queued = readRunStatus().phase == HeartbeatRunPhase.QUEUED
            val nextTriggerAt = readNextTriggerAt()
            close()
            queued && shouldRecoverQueuedHeartbeat(nextTriggerAt, System.currentTimeMillis())
        }
        if (triggerRequested) {
            requestRun(
                forceTrigger = forceTrigger,
                source = intent.getStringExtra(EXTRA_TRIGGER_SOURCE).orEmpty(),
                readOnlyTest = readOnlyTest,
                targetAssistantId = intent.getStringExtra(EXTRA_ASSISTANT_ID),
            )
        } else if (recoverQueuedRun) {
            requestRun(
                forceTrigger = false,
                source = "recovery",
                readOnlyTest = false,
                targetAssistantId = HeartbeatConfigStore(this).run {
                    readRunStatus().assistantId.also { close() }
                },
            )
        }
        return START_STICKY
    }

    private fun ensureScheduler() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = serviceScope.launch {
            while (true) {
                val store = HeartbeatConfigStore(this@HeartbeatForegroundService)
                val config = store.read()
                val nextTriggerAt = store.readNextTriggerAt()
                store.close()
                if (!config.enabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }

                val now = System.currentTimeMillis()
                when {
                    nextTriggerAt == null -> {
                        HeartbeatScheduler.scheduleNext(this@HeartbeatForegroundService, config)
                        delay(CHECK_INTERVAL_MILLIS)
                    }
                    nextTriggerAt > now -> {
                        delay(min(nextTriggerAt - now, CHECK_INTERVAL_MILLIS))
                    }
                    else -> {
                        requestRun(
                            forceTrigger = false,
                            source = "persistent",
                            readOnlyTest = false,
                        )
                        delay(POST_TRIGGER_PAUSE_MILLIS)
                    }
                }
            }
        }
    }

    private fun requestRun(
        forceTrigger: Boolean,
        source: String,
        readOnlyTest: Boolean,
        targetAssistantId: String? = null,
    ) {
        val triggerStore = HeartbeatConfigStore(this, targetAssistantId)
        val triggerConfig = triggerStore.read()
        triggerStore.close()
        val runAssistantId = targetAssistantId ?: triggerConfig.assistantId
        val autonomousTrigger = !readOnlyTest && !forceTrigger && (
            HeartbeatScheduleStore(this)
                .nextWakeAtMillis(triggerConfig.assistantId)
                ?.let { it <= System.currentTimeMillis() }
                == true
            )
        if (VoiceCallSessionRegistry.isActive()) {
            val store = HeartbeatConfigStore(this, runAssistantId)
            val config = store.read()
            store.recordRunStatus(
                phase = HeartbeatRunPhase.SKIPPED_BUSY,
                reason = HeartbeatRunReason.VOICE_CALL_ACTIVE,
                assistantId = runAssistantId,
                triggerSource = source,
                completionImpact = if (readOnlyTest) {
                    HeartbeatRunImpact.NEUTRAL
                } else {
                    HeartbeatRunImpact.SKIPPED
                },
            )
            store.close()
            if (!readOnlyTest && config.enabled) {
                HeartbeatScheduler.scheduleNext(this, config)
            }
            return
        }

        if (executionJob?.isActive == true) {
            val busyStore = HeartbeatConfigStore(this, runAssistantId)
            busyStore.recordRunStatus(
                phase = HeartbeatRunPhase.SKIPPED_BUSY,
                reason = HeartbeatRunReason.HEARTBEAT_ALREADY_RUNNING,
                assistantId = runAssistantId,
                triggerSource = source,
                completionImpact = if (readOnlyTest) {
                    HeartbeatRunImpact.NEUTRAL
                } else {
                    HeartbeatRunImpact.SKIPPED
                },
            )
            busyStore.close()
            return
        }

        val queuedStore = HeartbeatConfigStore(this, runAssistantId)
        val retryTrigger = !readOnlyTest && (
            queuedStore.readDiagnostics().nextRetryAtMillis
                ?.let { retryAt -> retryAt <= System.currentTimeMillis() }
                == true
            )
        if (!readOnlyTest) {
            queuedStore.recordNextTriggerAt(null)
        }
        queuedStore.recordRunStatus(
            phase = HeartbeatRunPhase.QUEUED,
            reason = if (readOnlyTest) HeartbeatRunReason.READ_ONLY_TEST else HeartbeatRunReason.NONE,
            assistantId = runAssistantId,
            triggerSource = source,
        )
        queuedStore.close()
        if (!readOnlyTest) HeartbeatScheduler.consumePendingTrigger(this, runAssistantId)

        executionJob = serviceScope.launch {
            var store: HeartbeatConfigStore? = null
            var gateAcquired = false
            val runStartedAtMillis = System.currentTimeMillis()
            try {
                val activeStore = HeartbeatConfigStore(this@HeartbeatForegroundService, runAssistantId)
                store = activeStore
                val config = activeStore.read()
                if (!config.enabled) {
                    activeStore.recordRunStatus(
                        phase = HeartbeatRunPhase.SKIPPED_DISABLED,
                        reason = HeartbeatRunReason.DISABLED,
                        assistantId = runAssistantId,
                        triggerSource = source,
                        startedAtMillis = runStartedAtMillis,
                        completionImpact = if (readOnlyTest) {
                            HeartbeatRunImpact.NEUTRAL
                        } else {
                            HeartbeatRunImpact.SKIPPED
                        },
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                if (runAssistantId != null &&
                    config.assistantId != null &&
                    config.assistantId != runAssistantId
                ) {
                    activeStore.recordRunStatus(
                        phase = HeartbeatRunPhase.SKIPPED_BUSY,
                        reason = HeartbeatRunReason.TARGET_CHANGED,
                        assistantId = runAssistantId,
                        triggerSource = source,
                        startedAtMillis = runStartedAtMillis,
                        completionImpact = if (readOnlyTest) {
                            HeartbeatRunImpact.NEUTRAL
                        } else {
                            HeartbeatRunImpact.SKIPPED
                        },
                    )
                    return@launch
                }
                when (
                    HeartbeatExecutionGate.tryAcquire(
                        store = activeStore,
                        config = config,
                        bypassMinimumInterval = forceTrigger || autonomousTrigger || retryTrigger,
                        recordClaim = !readOnlyTest,
                    )
                ) {
                    HeartbeatGateResult.ALREADY_RUNNING -> {
                        activeStore.recordRunStatus(
                            phase = HeartbeatRunPhase.SKIPPED_BUSY,
                            reason = HeartbeatRunReason.HEARTBEAT_ALREADY_RUNNING,
                            assistantId = runAssistantId,
                            triggerSource = source,
                            startedAtMillis = runStartedAtMillis,
                            completionImpact = if (readOnlyTest) {
                                HeartbeatRunImpact.NEUTRAL
                            } else {
                                HeartbeatRunImpact.SKIPPED
                            },
                        )
                        return@launch
                    }
                    HeartbeatGateResult.TOO_SOON -> {
                        activeStore.recordRunStatus(
                            phase = HeartbeatRunPhase.SKIPPED_BUSY,
                            reason = HeartbeatRunReason.MINIMUM_INTERVAL,
                            assistantId = runAssistantId,
                            triggerSource = source,
                            startedAtMillis = runStartedAtMillis,
                            completionImpact = if (readOnlyTest) {
                                HeartbeatRunImpact.NEUTRAL
                            } else {
                                HeartbeatRunImpact.SKIPPED
                            },
                        )
                        return@launch
                    }
                    HeartbeatGateResult.ACQUIRED -> Unit
                }
                gateAcquired = true
                activeStore.recordRunStatus(
                    phase = HeartbeatRunPhase.RUNNING,
                    reason = if (readOnlyTest) {
                        HeartbeatRunReason.READ_ONLY_TEST
                    } else {
                        HeartbeatRunReason.NONE
                    },
                    assistantId = runAssistantId,
                    triggerSource = source,
                    startedAtMillis = runStartedAtMillis,
                )
                HeartbeatNotifications.updateServiceStatus(this@HeartbeatForegroundService, generating = true)

                val result = withTimeout(config.generationTimeoutSeconds * 1_000L) {
                    HeartbeatGenerationWorkflow(this@HeartbeatForegroundService).run(
                        config = config,
                        mode = if (readOnlyTest) {
                            HeartbeatExecutionMode.READ_ONLY_TEST
                        } else {
                            HeartbeatExecutionMode.LIVE
                        },
                    )
                }
                activeStore.recordRunStatus(
                    phase = result.toRunPhase(),
                    detail = result.detail,
                    reason = result.reason,
                    assistantId = runAssistantId,
                    triggerSource = source,
                    startedAtMillis = runStartedAtMillis,
                    completionImpact = result.toRunImpact(readOnlyTest),
                )
            } catch (error: TimeoutCancellationException) {
                store?.recordRunStatus(
                    phase = HeartbeatRunPhase.TIMED_OUT,
                    reason = HeartbeatRunReason.TIMEOUT,
                    assistantId = runAssistantId,
                    triggerSource = source,
                    startedAtMillis = runStartedAtMillis,
                    completionImpact = if (readOnlyTest) {
                        HeartbeatRunImpact.NEUTRAL
                    } else {
                        HeartbeatRunImpact.FAILURE
                    },
                )
                Log.e(TAG, "Heartbeat generation timed out", error)
            } catch (error: CancellationException) {
                store?.recordRunStatus(
                    phase = HeartbeatRunPhase.CANCELLED,
                    reason = HeartbeatRunReason.CANCELLED,
                    assistantId = runAssistantId,
                    triggerSource = source,
                    startedAtMillis = runStartedAtMillis,
                    completionImpact = if (readOnlyTest) {
                        HeartbeatRunImpact.NEUTRAL
                    } else {
                        HeartbeatRunImpact.SKIPPED
                    },
                )
                Log.d(TAG, "Heartbeat generation cancelled")
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Heartbeat generation failed", error)
                store?.recordRunStatus(
                    phase = HeartbeatRunPhase.FAILED,
                    detail = error::class.simpleName,
                    reason = HeartbeatFailureClassifier.classify(error),
                    assistantId = runAssistantId,
                    triggerSource = source,
                    startedAtMillis = runStartedAtMillis,
                    completionImpact = if (readOnlyTest) {
                        HeartbeatRunImpact.NEUTRAL
                    } else {
                        HeartbeatRunImpact.FAILURE
                    },
                )
            } finally {
                withContext(NonCancellable) {
                    if (gateAcquired) {
                        HeartbeatExecutionGate.release()
                    }
                    if (!destroyed) {
                        HeartbeatNotifications.updateServiceStatus(
                            this@HeartbeatForegroundService,
                            generating = false,
                        )
                    }
                    store?.let { activeStore ->
                        val latest = activeStore.read()
                        // A diagnostic run must not consume, replace, or cancel the live alarm plan.
                        if (!readOnlyTest) {
                            if (latest.enabled) {
                                HeartbeatScheduler.scheduleNext(
                                    this@HeartbeatForegroundService,
                                    latest,
                                    intervalAnchorAtMillis = runStartedAtMillis,
                                )
                            } else {
                                HeartbeatScheduler.cancel(this@HeartbeatForegroundService)
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                        activeStore.close()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        executionJob?.cancel()
        schedulerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_FORCE_TRIGGER = "force_trigger"
        const val EXTRA_TRIGGER_SOURCE = "trigger_source"
        const val EXTRA_READ_ONLY_TEST = "read_only_test"
        const val EXTRA_ASSISTANT_ID = "assistant_id"
        private const val CHECK_INTERVAL_MILLIS = 60_000L
        private const val POST_TRIGGER_PAUSE_MILLIS = 30_000L
        private const val TAG = "HeartbeatForeground"

        fun startPersistent(context: Context) {
            val intent = Intent(context, HeartbeatForegroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                val detail = it.toHeartbeatDiagnosticDetail(stage = "persistent_start")
                Logging.log(
                    tag = "Heartbeat",
                    message = "persistent service start failed $detail",
                )
                val store = HeartbeatConfigStore(context)
                val config = store.read()
                store.recordRunStatus(
                    phase = HeartbeatRunPhase.FAILED,
                    detail = detail,
                    reason = HeartbeatRunReason.SERVICE_START_FAILURE,
                    triggerSource = "persistent_start",
                    completionImpact = HeartbeatRunImpact.FAILURE,
                )
                store.close()
                if (config.enabled) HeartbeatScheduler.scheduleNext(context, config)
            }
        }

        fun stopPersistent(context: Context) {
            context.stopService(Intent(context, HeartbeatForegroundService::class.java))
        }
    }
}

internal fun shouldRecoverQueuedHeartbeat(nextTriggerAt: Long?, now: Long): Boolean =
    nextTriggerAt == null || nextTriggerAt <= now

private fun HeartbeatGenerationResult.toRunPhase(): HeartbeatRunPhase = when (outcome) {
    HeartbeatGenerationOutcome.SENT -> HeartbeatRunPhase.SENT
    HeartbeatGenerationOutcome.PASS -> HeartbeatRunPhase.PASS
    HeartbeatGenerationOutcome.PENDING_USER -> HeartbeatRunPhase.SKIPPED_PENDING_USER
    HeartbeatGenerationOutcome.BUSY -> HeartbeatRunPhase.SKIPPED_BUSY
    HeartbeatGenerationOutcome.NO_MODEL -> HeartbeatRunPhase.SKIPPED_NO_MODEL
    HeartbeatGenerationOutcome.TESTED -> HeartbeatRunPhase.TESTED
}

private fun HeartbeatGenerationResult.toRunImpact(readOnlyTest: Boolean): HeartbeatRunImpact {
    if (readOnlyTest) return HeartbeatRunImpact.NEUTRAL
    return when (outcome) {
        HeartbeatGenerationOutcome.SENT,
        HeartbeatGenerationOutcome.PASS,
        -> HeartbeatRunImpact.SUCCESS
        HeartbeatGenerationOutcome.NO_MODEL -> HeartbeatRunImpact.FAILURE
        HeartbeatGenerationOutcome.PENDING_USER,
        HeartbeatGenerationOutcome.BUSY,
        -> HeartbeatRunImpact.SKIPPED
        HeartbeatGenerationOutcome.TESTED -> HeartbeatRunImpact.NEUTRAL
    }
}

private enum class HeartbeatGateResult {
    ACQUIRED,
    ALREADY_RUNNING,
    TOO_SOON,
}

private object HeartbeatExecutionGate {
    private val running = AtomicBoolean(false)
    private val claimLock = Any()

    fun tryAcquire(
        store: HeartbeatConfigStore,
        config: HeartbeatConfig,
        bypassMinimumInterval: Boolean,
        recordClaim: Boolean,
    ): HeartbeatGateResult {
        if (!running.compareAndSet(false, true)) return HeartbeatGateResult.ALREADY_RUNNING
        val claimed = synchronized(claimLock) {
            val now = System.currentTimeMillis()
            val minimumInterval = config.minIntervalMinutes * 60_000L
            if (!bypassMinimumInterval && now - store.lastClaimAt() < minimumInterval) {
                false
            } else {
                if (recordClaim) store.recordLastClaimAt(now)
                true
            }
        }
        if (!claimed) running.set(false)
        return if (claimed) HeartbeatGateResult.ACQUIRED else HeartbeatGateResult.TOO_SOON
    }

    fun release() {
        running.set(false)
    }
}
