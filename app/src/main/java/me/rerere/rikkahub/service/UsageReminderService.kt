package me.rerere.rikkahub.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.common.android.Logging
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.EXTRA_OPEN_USAGE_TRACKER
import me.rerere.rikkahub.R
import me.rerere.rikkahub.USAGE_LIMIT_REMINDER_CHANNEL_ID
import me.rerere.rikkahub.USAGE_REMINDER_MONITOR_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.usagetracker.UsageReminderAppState
import me.rerere.usagetracker.UsageReminderConfig
import me.rerere.usagetracker.UsageReminderLock
import me.rerere.usagetracker.UsageReminderRule
import me.rerere.usagetracker.UsageReminderState
import me.rerere.usagetracker.UsageStatsPeriod
import me.rerere.usagetracker.UsageStatsReader
import me.rerere.usagetracker.todayKey
import org.koin.android.ext.android.inject
import java.text.DateFormat
import java.util.Date
import kotlin.math.absoluteValue

private const val TAG = "UsageReminderService"

class UsageReminderService : Service() {
    private val settingsStore by inject<SettingsStore>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var reader: UsageStatsReader
    private var monitorJob: Job? = null
    private var lockJob: Job? = null
    private var scheduledUnlockAtMillis: Long = 0L
    private var lockOverlayView: View? = null
    private var lockOverlaySignature: String? = null
    private var lockNoticeView: View? = null
    private var lockNoticeSignature: String? = null
    private var lockNoticeJob: Job? = null
    private var lastLockNotificationSignature: String? = null
    private var lastTargetRedirectPackageName: String? = null
    private var lastTargetRedirectAtMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        reader = UsageStatsReader(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOCK -> {
                val lockedUntilMillis = intent.getLongExtra(EXTRA_LOCKED_UNTIL_MILLIS, 0L)
                val reason = intent.getStringExtra(EXTRA_LOCK_REASON).orEmpty()
                val source = intent.getStringExtra(EXTRA_LOCK_SOURCE).orEmpty().ifBlank { "ai_tool" }
                val targetPackageName = intent.getStringExtra(EXTRA_LOCK_TARGET_PACKAGE_NAME)
                val targetLabel = intent.getStringExtra(EXTRA_LOCK_TARGET_LABEL).orEmpty()
                scope.launch {
                    val settings = settingsStore.settingsFlowRaw.first()
                    if (!settings.usageReminderConfig.lockEnabled) {
                        logUsageLock("lockUntil: AI lock tool disabled in usage settings")
                        return@launch
                    }
                    lockUntil(
                        lockedUntilMillis = lockedUntilMillis,
                        reason = reason,
                        source = source,
                        targetPackageName = targetPackageName,
                        targetLabel = targetLabel,
                    )
                }
                return START_STICKY
            }

            ACTION_UNLOCK -> {
                scope.launch { clearLock() }
                return START_STICKY
            }

            ACTION_IGNORE_TODAY -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (packageName != null) {
                    scope.launch { ignoreToday(packageName) }
                }
                return START_STICKY
            }

            ACTION_STOP -> {
                scope.launch {
                    val settings = settingsStore.settingsFlowRaw.first()
                    if (!settings.usageReminderConfig.lockEnabled) {
                        clearLock()
                    }
                    val activeLock = settingsStore.settingsFlowRaw.first().usageReminderState.activeLock
                        ?.takeIf {
                            settings.usageReminderConfig.lockEnabled &&
                                it.source == "ai_tool" &&
                                it.lockedUntilMillis > System.currentTimeMillis()
                        }
                    if (activeLock != null) {
                        startMonitoring(restart = true)
                    } else {
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }

            else -> {
                scope.launch {
                    val settings = settingsStore.settingsFlowRaw.first()
                    if (!settings.usageReminderConfig.lockEnabled) {
                        clearLock()
                    }
                    startMonitoring()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        lockJob?.cancel()
        removeLockOverlay()
        removeLockNotice()
        scope.cancel()
        super.onDestroy()
    }

    private fun logUsageLock(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.i(TAG, message)
            Logging.log("UsageLock", message)
        } else {
            Log.e(TAG, message, throwable)
            Logging.log("UsageLock", "$message\nerror=${throwable.message.orEmpty()}")
        }
    }

    private fun startMonitoring(restart: Boolean = false) {
        if (!hasNotificationPermission(this)) {
            logUsageLock("startMonitoring: notification permission missing, stop service")
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID_MONITOR, buildMonitorNotification())
        if (restart && monitorJob?.isActive == true) {
            logUsageLock("startMonitoring: restart requested, wake monitor loop")
            monitorJob?.cancel()
            monitorJob = null
        }
        if (monitorJob?.isActive == true) {
            logUsageLock("startMonitoring: monitor already active")
            return
        }
        logUsageLock("startMonitoring: monitor loop started")
        monitorJob = scope.launch {
            while (isActive) {
                var nextDelayMillis = CHECK_INTERVAL_MILLIS
                val checkStartedAt = SystemClock.elapsedRealtime()
                runCatching { checkUsageRules() }
                    .onSuccess { nextDelayMillis = it }
                    .onFailure { logUsageLock("checkUsageRules failed", it) }
                val elapsedMillis = SystemClock.elapsedRealtime() - checkStartedAt
                delay((nextDelayMillis - elapsedMillis).coerceAtLeast(MIN_MONITOR_DELAY_MILLIS))
            }
        }
    }

    private suspend fun checkUsageRules(): Long {
        val settings = settingsStore.settingsFlowRaw.first()
        val now = System.currentTimeMillis()
        val storedLock = settings.usageReminderState.activeLock
        val activeLock = storedLock?.takeIf {
            settings.usageReminderConfig.lockEnabled && it.source == "ai_tool"
        }
        if (storedLock != null && activeLock == null) {
            logUsageLock(
                "checkUsageRules: clearing disabled or non-AI lock source=${storedLock.source} " +
                    "target=${storedLock.targetPackageName}"
            )
            clearLock()
        } else if (activeLock != null) {
            if (activeLock.lockedUntilMillis > now) {
                logUsageLock(
                    "checkUsageRules: active lock target=${activeLock.targetPackageName} " +
                        "label=${activeLock.targetLabel} until=${activeLock.lockedUntilMillis}"
                )
                sendLockNotification(activeLock)
                syncLockOverlay(activeLock)
                scheduleUnlock(activeLock.lockedUntilMillis)
                return activeLock.nextCheckDelayMillis()
            } else {
                logUsageLock(
                    "checkUsageRules: active lock expired target=${activeLock.targetPackageName} " +
                        "until=${activeLock.lockedUntilMillis}, clearing"
                )
                clearLock()
            }
        }

        val enabledRules = settings.usageReminderConfig.rules.filter { it.enabled }
        val hasActiveLock = activeLock
            ?.lockedUntilMillis
            ?.let { it > now } == true
        if (enabledRules.isEmpty()) {
            if (!hasActiveLock) stopSelf()
            return CHECK_INTERVAL_MILLIS
        }
        if (!hasNotificationPermission(this) || !reader.hasUsageAccess()) {
            stopSelf()
            return CHECK_INTERVAL_MILLIS
        }

        val today = todayKey()
        val state = settings.usageReminderState.takeIf { it.date == today }
            ?.copy(activeLock = activeLock?.takeIf { it.lockedUntilMillis > now })
            ?: UsageReminderState(
                date = today,
                activeLock = activeLock?.takeIf { it.lockedUntilMillis > now },
            )
        val earliestKnownEvent = enabledRules
            .mapNotNull { rule -> state.appStates[rule.packageName]?.lastEventTimeMillis }
            .minOrNull()
            ?: (System.currentTimeMillis() - CHECK_LOOKBACK_MILLIS)
        val events = reader.loadForegroundEvents(
            startMillis = (earliestKnownEvent - 1_000L).coerceAtLeast(0L),
            endMillis = System.currentTimeMillis(),
        )

        val rulesByPackage = enabledRules.associateBy { it.packageName }
        val nextStates = state.appStates.toMutableMap()
        var nextLock = state.activeLock
        var changed = state.date != settings.usageReminderState.date

        for (event in events) {
            val rule = rulesByPackage[event.packageName] ?: continue
            val current = nextStates[event.packageName] ?: UsageReminderAppState()
            if (event.timestampMillis <= current.lastEventTimeMillis) continue

            val baseState = current.copy(lastEventTimeMillis = event.timestampMillis)
            if (baseState.ignored) {
                nextStates[event.packageName] = baseState
                changed = true
                continue
            }
            nextStates[event.packageName] = baseState
            changed = true
        }

        for (rule in enabledRules) {
            val current = nextStates[rule.packageName] ?: UsageReminderAppState()
            if (current.ignored) continue

            val usageMillis = reader.loadUsageMillis(rule.packageName, UsageStatsPeriod.Today)
            if (usageMillis < rule.thresholdMinutes * 60_000L) continue
            if (usageMillis <= current.lastReminderUsageMillis) continue

            val remindedState = current.copy(
                reminderCount = current.reminderCount + 1,
                lastReminderUsageMillis = usageMillis,
            )
            nextStates[rule.packageName] = remindedState
            sendLimitNotification(
                rule = rule,
                usageMillis = usageMillis,
                reminderCount = remindedState.reminderCount,
                reminderMessages = settings.usageReminderConfig.reminderMessages,
            )
            changed = true
        }

        if (changed || nextLock != state.activeLock) {
            settingsStore.update { current ->
                current.copy(
                    usageReminderState = UsageReminderState(
                        date = today,
                        appStates = nextStates,
                        activeLock = nextLock,
                    )
                )
            }
        }
        return nextLock
            ?.takeIf { it.lockedUntilMillis > now }
            ?.nextCheckDelayMillis()
            ?: CHECK_INTERVAL_MILLIS
    }

    private suspend fun lockUntil(
        lockedUntilMillis: Long,
        reason: String,
        source: String,
        targetPackageName: String?,
        targetLabel: String,
    ) {
        val now = System.currentTimeMillis()
        if (lockedUntilMillis <= now) return
        if (!hasNotificationPermission(this)) return
        startForeground(NOTIFICATION_ID_MONITOR, buildMonitorNotification())
        val lock = UsageReminderLock(
            lockedUntilMillis = lockedUntilMillis,
            reason = reason,
            source = source,
            targetPackageName = targetPackageName,
            targetLabel = targetLabel,
        )
        settingsStore.update { current ->
            current.copy(
                usageReminderState = current.usageReminderState.copy(activeLock = lock)
            )
        }
        logUsageLock(
            "lockUntil: accepted target=$targetPackageName label=$targetLabel source=$source " +
                "until=$lockedUntilMillis usageAccess=${reader.hasUsageAccess()} " +
                "overlay=${canDrawOverlays(this)} monitorActive=${monitorJob?.isActive == true}"
        )
        sendLockNotification(lock)
        syncLockOverlay(lock)
        scheduleUnlock(lock.lockedUntilMillis)
        startMonitoring(restart = true)
    }

    private suspend fun clearLock(cancelScheduledUnlock: Boolean = true) {
        logUsageLock("clearLock: cancelScheduledUnlock=$cancelScheduledUnlock")
        if (cancelScheduledUnlock) {
            lockJob?.cancel()
            lockJob = null
        }
        scheduledUnlockAtMillis = 0L
        removeLockOverlay()
        removeLockNotice()
        settingsStore.update { current ->
            current.copy(
                usageReminderState = current.usageReminderState.copy(activeLock = null)
            )
        }
    }

    private fun showLockOverlay(lock: UsageReminderLock) {
        if (lock.targetPackageName == packageName) {
            logUsageLock("showLockOverlay: self lock uses in-app input blocking, no system overlay")
            removeLockOverlay()
            return
        }
        if (!canDrawOverlays(this)) {
            logUsageLock("showLockOverlay: overlay permission missing, send notification fallback")
            sendLockNotification(lock)
            return
        }
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val existing = lockOverlayView
        val compactLock = lock.targetPackageName == packageName
        val signature = buildString {
            append(if (compactLock) "compact" else "full")
            append('|')
            append(lock.targetPackageName.orEmpty())
            append('|')
            append(lock.lockedUntilMillis)
            append('|')
            append(lock.reason)
        }
        if (existing != null && lockOverlaySignature == signature) {
            logUsageLock("showLockOverlay: overlay already shown signature=$signature")
            return
        }
        if (existing != null) {
            windowManager.removeView(existing)
            lockOverlayView = null
            lockOverlaySignature = null
        }

        if (compactLock) {
            showCompactLockWindow(windowManager, lock)
        } else {
            showFrostedLockOverlay(windowManager, lock)
        }
        logUsageLock(
            "showLockOverlay: overlay shown target=${lock.targetPackageName} full=${!compactLock} " +
                "until=${lock.lockedUntilMillis}"
        )
        lockOverlaySignature = signature
    }

    private suspend fun syncLockOverlay(lock: UsageReminderLock) {
        val targetPackageName = lock.targetPackageName
        val foregroundPackageName = if (targetPackageName == null) {
            null
        } else {
            reader.loadCurrentForegroundPackageName()
        }
        val action = when {
            targetPackageName == null -> "notification_only"
            targetPackageName == packageName -> "self_app_input_block"
            foregroundPackageName == targetPackageName -> "redirect_home"
            else -> "no_match_remove"
        }
        logUsageLock(
            "syncLockOverlay: target=$targetPackageName label=${lock.targetLabel} " +
                "foreground=$foregroundPackageName self=$packageName action=$action source=${lock.source} " +
                "until=${lock.lockedUntilMillis} usageAccess=${reader.hasUsageAccess()} " +
                "overlay=${canDrawOverlays(this)} monitorActive=${monitorJob?.isActive == true}"
        )
        when {
            targetPackageName == null -> {
                removeLockOverlay()
                sendLockNotification(lock)
            }
            targetPackageName == packageName -> removeLockOverlay()
            foregroundPackageName == targetPackageName -> {
                redirectTargetToHome(lock)
                showCenterLockNotice(lock)
            }
            else -> removeLockOverlay()
        }
    }

    private fun redirectTargetToHome(lock: UsageReminderLock) {
        val targetPackageName = lock.targetPackageName ?: return
        val now = System.currentTimeMillis()
        if (
            targetPackageName == lastTargetRedirectPackageName &&
            now - lastTargetRedirectAtMillis < TARGET_REDIRECT_COOLDOWN_MILLIS
        ) {
            logUsageLock(
                "redirectTargetToHome: cooldown skip target=$targetPackageName " +
                    "elapsed=${now - lastTargetRedirectAtMillis}"
            )
            return
        }
        lastTargetRedirectPackageName = targetPackageName
        lastTargetRedirectAtMillis = now
        logUsageLock("redirectTargetToHome: launching HOME target=$targetPackageName")
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }.onSuccess {
            logUsageLock("redirectTargetToHome: HOME intent sent target=$targetPackageName")
        }.onFailure {
            logUsageLock("redirectTargetToHome: HOME intent failed target=$targetPackageName", it)
        }
    }

    private fun showCenterLockNotice(lock: UsageReminderLock) {
        if (!canDrawOverlays(this)) {
            logUsageLock(
                "showCenterLockNotice: overlay permission missing target=${lock.targetPackageName}, " +
                    "send notification fallback"
            )
            sendLockNotification(lock)
            return
        }
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val unlockText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(lock.lockedUntilMillis))
        val targetText = lock.targetLabel
            .ifBlank { lock.targetPackageName.orEmpty() }
            .ifBlank { getString(R.string.usage_lock_overlay_title) }
        val signature = buildString {
            append("notice|")
            append(lock.targetPackageName.orEmpty())
            append('|')
            append(lock.lockedUntilMillis)
            append('|')
            append(lock.reason)
        }
        if (lockNoticeView != null && lockNoticeSignature == signature) {
            logUsageLock("showCenterLockNotice: notice already shown signature=$signature")
            return
        }
        removeLockNotice()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28.dp(), 22.dp(), 28.dp(), 24.dp())
            background = frostedPanelDrawable(
                color = Color.argb(218, 255, 255, 255),
                radius = 28.dp().toFloat(),
            )
            isClickable = false
            isFocusable = false
        }
        content.addView(TextView(this).apply {
            text = targetText
            setTextColor(Color.rgb(15, 23, 42))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.usage_lock_overlay_until, unlockText)
            setTextColor(Color.rgb(51, 65, 85))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 8.dp(), 0, 0)
        })
        if (lock.reason.isNotBlank()) {
            content.addView(TextView(this).apply {
                text = lock.reason
                setTextColor(Color.rgb(71, 85, 105))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 8.dp(), 0, 0)
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            lockOverlayFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            ),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 24
            }
        }
        runCatching {
            windowManager.addView(content, params)
        }.onSuccess {
            logUsageLock(
                "showCenterLockNotice: notice added target=${lock.targetPackageName} " +
                    "until=${lock.lockedUntilMillis}"
            )
            lockNoticeView = content
            lockNoticeSignature = signature
            lockNoticeJob?.cancel()
            lockNoticeJob = scope.launch {
                delay(TARGET_REDIRECT_NOTICE_MILLIS)
                if (lockNoticeSignature == signature) {
                    logUsageLock("showCenterLockNotice: auto remove notice target=${lock.targetPackageName}")
                    removeLockNotice()
                }
            }
        }.onFailure {
            logUsageLock("showCenterLockNotice: addView failed target=${lock.targetPackageName}", it)
            sendLockNotification(lock)
        }
    }

    private fun removeLockNotice() {
        lockNoticeJob?.cancel()
        lockNoticeJob = null
        val view = lockNoticeView ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
        lockNoticeView = null
        lockNoticeSignature = null
    }

    private fun showFrostedLockOverlay(windowManager: WindowManager, lock: UsageReminderLock) {
        val unlockText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(lock.lockedUntilMillis))
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(112, 13, 13, 31))
            isClickable = true
            isFocusable = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)
            background = frostedPanelDrawable(
                color = Color.argb(28, 255, 255, 255),
                radius = 36f,
            )
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.usage_lock_overlay_title)
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.usage_lock_overlay_until, unlockText)
            setTextColor(Color.argb(194, 255, 255, 255))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 0)
        })
        if (lock.reason.isNotBlank()) {
            content.addView(TextView(this).apply {
                text = lock.reason
                setTextColor(Color.argb(154, 255, 255, 255))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, 18, 0, 0)
            })
        }
        container.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = 48
                rightMargin = 48
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            lockOverlayFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            ),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 48
            }
        }
        windowManager.addView(container, params)
        lockOverlayView = container
        logUsageLock("showFrostedLockOverlay: addView success target=${lock.targetPackageName}")
    }

    private fun showCompactLockWindow(windowManager: WindowManager, lock: UsageReminderLock) {
        val unlockText = DateFormat.getTimeInstance(DateFormat.SHORT)
            .format(Date(lock.lockedUntilMillis))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = 112.dp()
            setPadding(24.dp(), 16.dp(), 24.dp(), 18.dp())
            background = frostedPanelDrawable(
                color = Color.argb(196, 255, 255, 255),
                radius = 28.dp().toFloat(),
            )
            isClickable = true
            isFocusable = false
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.usage_lock_compact_title)
            setTextColor(Color.rgb(15, 23, 42))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.usage_lock_overlay_until, unlockText)
            setTextColor(Color.rgb(51, 65, 85))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 6.dp(), 0, 0)
        })
        if (lock.reason.isNotBlank()) {
            content.addView(TextView(this).apply {
                text = lock.reason
                setTextColor(Color.rgb(71, 85, 105))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 6.dp(), 0, 0)
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            lockOverlayFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            ),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 12.dp()
            horizontalMargin = 0.03f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 24
            }
        }
        windowManager.addView(content, params)
        lockOverlayView = content
        logUsageLock("showCompactLockWindow: addView success target=${lock.targetPackageName}")
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun frostedPanelDrawable(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(1, Color.argb(44, 255, 255, 255))
        }
    }

    private fun removeLockOverlay() {
        val view = lockOverlayView ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
        lockOverlayView = null
        lockOverlaySignature = null
    }

    private fun scheduleUnlock(lockedUntilMillis: Long) {
        if (lockJob?.isActive == true && scheduledUnlockAtMillis == lockedUntilMillis) {
            logUsageLock("scheduleUnlock: already scheduled until=$lockedUntilMillis")
            return
        }
        lockJob?.cancel()
        scheduledUnlockAtMillis = lockedUntilMillis
        logUsageLock("scheduleUnlock: scheduled until=$lockedUntilMillis")
        lockJob = scope.launch {
            val waitMillis = (lockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(waitMillis)
            logUsageLock("scheduleUnlock: time reached until=$lockedUntilMillis, clearing")
            clearLock(cancelScheduledUnlock = false)
            lockJob = null
        }
    }

    private fun UsageReminderLock.nextCheckDelayMillis(): Long {
        return if (targetPackageName == null) {
            CHECK_INTERVAL_MILLIS
        } else {
            ACTIVE_LOCK_TARGET_CHECK_INTERVAL_MILLIS
        }
    }

    private fun sendLockNotification(lock: UsageReminderLock) {
        val signature = buildString {
            append(lock.createdAtMillis)
            append('|')
            append(lock.lockedUntilMillis)
            append('|')
            append(lock.targetPackageName.orEmpty())
            append('|')
            append(lock.reason)
        }
        if (lastLockNotificationSignature == signature) {
            return
        }
        val unlockText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(lock.lockedUntilMillis))
        val reasonText = lock.reason.ifBlank { getString(R.string.usage_lock_overlay_title) }
        val content = if (canDrawOverlays(this)) {
            buildString {
                append(reasonText)
                append('\n')
                append(getString(R.string.usage_lock_overlay_until, unlockText))
            }
        } else {
            getString(
                R.string.usage_lock_notification_content,
                reasonText,
                unlockText,
            )
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID_LOCK,
                NotificationCompat.Builder(this, USAGE_LIMIT_REMINDER_CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(getString(R.string.usage_lock_notification_title))
                    .setContentText(content)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .build()
            )
            lastLockNotificationSignature = signature
        }.onFailure {
            logUsageLock("sendLockNotification: notify failed target=${lock.targetPackageName}", it)
        }
    }

    private suspend fun ignoreToday(packageName: String) {
        settingsStore.update { settings ->
            val today = todayKey()
            val state = settings.usageReminderState.takeIf { it.date == today } ?: UsageReminderState(date = today)
            val nextStates = state.appStates.toMutableMap()
            val current = nextStates[packageName] ?: UsageReminderAppState()
            nextStates[packageName] = current.copy(ignored = true)
            settings.copy(
                usageReminderState = state.copy(appStates = nextStates)
            )
        }
        NotificationManagerCompat.from(this).cancel(notificationIdFor(packageName))
    }

    private fun buildMonitorNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_USAGE_TRACKER,
            Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_USAGE_TRACKER, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, USAGE_REMINDER_MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.usage_reminder_monitor_title))
            .setContentText(getString(R.string.usage_reminder_monitor_desc))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun sendLimitNotification(
        rule: UsageReminderRule,
        usageMillis: Long,
        reminderCount: Int,
        reminderMessages: List<String>,
    ) {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_USAGE_TRACKER + notificationIdFor(rule.packageName),
            Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_USAGE_TRACKER, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val content = buildReminderContent(rule.label, usageMillis, reminderMessages)
        val builder = NotificationCompat.Builder(this, USAGE_LIMIT_REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(
                getString(
                    R.string.usage_reminder_limit_title,
                    rule.label,
                    rule.thresholdMinutes,
                )
            )
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        if (reminderCount >= IGNORE_ACTION_START_COUNT) {
            builder.addAction(
                R.drawable.small_icon,
                getString(R.string.usage_reminder_ignore_today),
                PendingIntent.getService(
                    this,
                    REQUEST_IGNORE_BASE + notificationIdFor(rule.packageName),
                    Intent(this, UsageReminderService::class.java).apply {
                        action = ACTION_IGNORE_TODAY
                        putExtra(EXTRA_PACKAGE_NAME, rule.packageName)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        NotificationManagerCompat.from(this).notify(notificationIdFor(rule.packageName), builder.build())
    }

    private fun buildReminderContent(appLabel: String, usageMillis: Long, reminderMessages: List<String>): String {
        val message = reminderMessages.filter { it.isNotBlank() }.randomOrNull().orEmpty()
        return getString(
            R.string.usage_reminder_limit_content,
            appLabel,
            formatDuration(usageMillis),
            message,
        ).trimEnd()
    }

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60_000L
        val hours = minutes / 60L
        val restMinutes = minutes % 60L
        return when {
            hours > 0L -> getString(R.string.usage_reminder_duration_hours, hours, restMinutes)
            minutes > 0L -> getString(R.string.usage_reminder_duration_minutes, minutes)
            millis > 0L -> getString(R.string.usage_reminder_duration_less_than_minute)
            else -> getString(R.string.usage_reminder_duration_zero)
        }
    }

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.usage_reminder.START"
        const val ACTION_STOP = "me.rerere.rikkahub.usage_reminder.STOP"
        const val ACTION_IGNORE_TODAY = "me.rerere.rikkahub.usage_reminder.IGNORE_TODAY"
        const val ACTION_LOCK = "me.rerere.rikkahub.usage_reminder.LOCK"
        const val ACTION_UNLOCK = "me.rerere.rikkahub.usage_reminder.UNLOCK"
        private const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_LOCKED_UNTIL_MILLIS = "lockedUntilMillis"
        const val EXTRA_LOCK_REASON = "lockReason"
        const val EXTRA_LOCK_SOURCE = "lockSource"
        const val EXTRA_LOCK_TARGET_PACKAGE_NAME = "lockTargetPackageName"
        const val EXTRA_LOCK_TARGET_LABEL = "lockTargetLabel"
        private const val NOTIFICATION_ID_MONITOR = 200_100
        private const val NOTIFICATION_ID_LOCK = 200_101
        private const val REQUEST_OPEN_USAGE_TRACKER = 200_200
        private const val REQUEST_IGNORE_BASE = 200_300
        private const val CHECK_INTERVAL_MILLIS = 1_000L
        private const val MIN_MONITOR_DELAY_MILLIS = 100L
        private const val ACTIVE_LOCK_TARGET_CHECK_INTERVAL_MILLIS = 1_000L
        private const val TARGET_REDIRECT_NOTICE_MILLIS = 10_000L
        private const val TARGET_REDIRECT_COOLDOWN_MILLIS = 800L
        private const val CHECK_LOOKBACK_MILLIS = 60_000L
        private const val IGNORE_ACTION_START_COUNT = 3

        fun sync(context: Context, config: UsageReminderConfig) {
            val shouldStart = config.rules.any { it.enabled }
            val intent = Intent(context, UsageReminderService::class.java).apply {
                action = if (shouldStart) ACTION_START else ACTION_STOP
            }
            if (shouldStart && hasNotificationPermission(context)) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun lock(
            context: Context,
            lockedUntilMillis: Long,
            reason: String,
            source: String = "ai_tool",
            targetPackageName: String? = null,
            targetLabel: String = "",
        ) {
            val intent = Intent(context, UsageReminderService::class.java).apply {
                action = ACTION_LOCK
                putExtra(EXTRA_LOCKED_UNTIL_MILLIS, lockedUntilMillis)
                putExtra(EXTRA_LOCK_REASON, reason)
                putExtra(EXTRA_LOCK_SOURCE, source)
                putExtra(EXTRA_LOCK_TARGET_PACKAGE_NAME, targetPackageName)
                putExtra(EXTRA_LOCK_TARGET_LABEL, targetLabel)
            }
            if (hasNotificationPermission(context)) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun unlock(context: Context) {
            context.startService(
                Intent(context, UsageReminderService::class.java).apply {
                    action = ACTION_UNLOCK
                }
            )
        }

        fun hasNotificationPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        fun canDrawOverlays(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        }

        private fun overlayWindowType(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        }

        private fun lockOverlayFlags(flags: Int): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            } else {
                flags
            }
        }

        private fun notificationIdFor(packageName: String): Int {
            return 210_000 + packageName.hashCode().absoluteValue % 10_000
        }
    }
}
