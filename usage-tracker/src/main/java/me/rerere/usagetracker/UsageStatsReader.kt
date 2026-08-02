package me.rerere.usagetracker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

private const val FOREGROUND_STATE_LOOKBACK_MILLIS = 24L * 60L * 60L * 1000L

class UsageStatsReader(private val context: Context) {
    private val appContext = context.applicationContext

    fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService<AppOpsManager>() ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings() {
        val packageUri = Uri.parse("package:${appContext.packageName}")
        val intents = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        val packageManager = appContext.packageManager
        val intent = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: intents.last()
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun loadUsage(period: UsageStatsPeriod): List<AppUsageSummary> = withContext(Dispatchers.IO) {
        val (startMillis, endMillis) = period.toWindowMillis()
        val usageStatsManager = appContext.getSystemService<UsageStatsManager>() ?: return@withContext emptyList()
        val packageManager = appContext.packageManager
        val usageByPackage = usageStatsManager.queryUsageValues(startMillis, endMillis)
        val installedApps = packageManager.loadInstalledApps()

        (installedApps.keys + usageByPackage.keys)
            .distinct()
            .map { packageName ->
                val usage = usageByPackage[packageName]
                AppUsageSummary(
                    packageName = packageName,
                    label = installedApps[packageName] ?: packageManager.loadLabel(packageName),
                    icon = packageManager.loadIconOrNull(packageName),
                    totalTimeForegroundMillis = usage?.totalTimeForegroundMillis ?: 0L,
                    lastTimeUsedMillis = usage?.lastTimeUsedMillis ?: 0L,
                    launchCount = usage?.launchCount ?: 0,
                )
            }
            .sortedWith(
                compareByDescending<AppUsageSummary> { it.totalTimeForegroundMillis }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
    }

    suspend fun loadUsageMillis(packageName: String, period: UsageStatsPeriod): Long = withContext(Dispatchers.IO) {
        val (startMillis, endMillis) = period.toWindowMillis()
        val usageStatsManager = appContext.getSystemService<UsageStatsManager>() ?: return@withContext 0L
        usageStatsManager.queryUsageValues(startMillis, endMillis)[packageName]?.totalTimeForegroundMillis ?: 0L
    }

    suspend fun loadForegroundEvents(startMillis: Long, endMillis: Long): List<ForegroundAppEvent> =
        withContext(Dispatchers.IO) {
            val usageStatsManager = appContext.getSystemService<UsageStatsManager>() ?: return@withContext emptyList()
            val events = usageStatsManager.queryEvents(startMillis, endMillis)
            val event = UsageEvents.Event()
            buildList {
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType.isLaunchEvent()) {
                        val packageName = event.packageName ?: continue
                        add(
                            ForegroundAppEvent(
                                packageName = packageName,
                                timestampMillis = event.timeStamp,
                            )
                        )
                    }
                }
            }.sortedBy { it.timestampMillis }
        }

    suspend fun loadCurrentForegroundPackageName(): String? = withContext(Dispatchers.IO) {
        val usageStatsManager = appContext.getSystemService<UsageStatsManager>() ?: return@withContext null
        val nowMillis = System.currentTimeMillis()
        val queryStartMillis = (nowMillis - FOREGROUND_STATE_LOOKBACK_MILLIS).coerceAtLeast(0L)
        usageStatsManager.loadCurrentForegroundPackageFromEvents(queryStartMillis, nowMillis)
            ?: runCatching {
                usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    queryStartMillis,
                    nowMillis,
                )
                    .filter { it.lastTimeUsed > 0L }
                    .maxByOrNull { it.lastTimeUsed }
                    ?.packageName
            }.getOrNull()
    }

    private fun UsageStatsManager.loadCurrentForegroundPackageFromEvents(
        startMillis: Long,
        endMillis: Long,
    ): String? {
        val events = queryEvents(startMillis, endMillis)
        val latestForegroundStartMillisByPackage = mutableMapOf<String, Long>()
        val latestForegroundEndMillisByPackage = mutableMapOf<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when {
                event.eventType.isForegroundStartEvent() -> {
                    latestForegroundStartMillisByPackage[packageName] = event.timeStamp
                }
                event.eventType.isForegroundEndEvent() -> {
                    latestForegroundEndMillisByPackage[packageName] = event.timeStamp
                }
            }
        }
        return latestForegroundStartMillisByPackage
            .asSequence()
            .filter { (packageName, startMillis) ->
                startMillis > (latestForegroundEndMillisByPackage[packageName] ?: Long.MIN_VALUE)
            }
            .maxByOrNull { (_, startMillis) -> startMillis }
            ?.key
    }

    private fun PackageManager.loadInstalledApps(): Map<String, String> {
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            getInstalledApplications(0)
        }
        return apps.associate { it.packageName to loadLabel(it) }
    }

    private fun PackageManager.loadLabel(applicationInfo: ApplicationInfo): String {
        return runCatching {
            getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(applicationInfo.packageName)
    }

    private fun PackageManager.loadLabel(packageName: String): String {
        return runCatching {
            getApplicationLabel(getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    private fun PackageManager.loadIconOrNull(packageName: String) = runCatching {
        getApplicationIcon(packageName)
    }.getOrNull()

    private fun UsageStatsManager.queryUsageValues(startMillis: Long, endMillis: Long): Map<String, UsageValues> {
        val nowMillis = System.currentTimeMillis()
        val effectiveEndMillis = min(endMillis, nowMillis)
        if (effectiveEndMillis <= startMillis) return emptyMap()

        val queryStartMillis = (startMillis - FOREGROUND_STATE_LOOKBACK_MILLIS).coerceAtLeast(0L)
        val activeStarts = mutableMapOf<String, Long>()
        val usageByPackage = mutableMapOf<String, MutableUsageValues>()
        val events = queryEvents(queryStartMillis, effectiveEndMillis)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            val timestamp = event.timeStamp
            when {
                event.eventType.isForegroundStartEvent() -> {
                    val wasActive = activeStarts.containsKey(packageName)
                    activeStarts.putIfAbsent(packageName, timestamp)
                    if (timestamp in startMillis until effectiveEndMillis) {
                        val usage = usageByPackage.getOrPut(packageName) { MutableUsageValues() }
                        usage.lastTimeUsedMillis = max(usage.lastTimeUsedMillis, timestamp)
                        if (!wasActive) {
                            usage.launchCount++
                        }
                    }
                }

                event.eventType.isForegroundEndEvent() -> {
                    val activeStartMillis = activeStarts.remove(packageName) ?: continue
                    val overlapStartMillis = max(activeStartMillis, startMillis)
                    val overlapEndMillis = min(timestamp, effectiveEndMillis)
                    if (overlapEndMillis > overlapStartMillis) {
                        val usage = usageByPackage.getOrPut(packageName) { MutableUsageValues() }
                        usage.totalTimeForegroundMillis += overlapEndMillis - overlapStartMillis
                        usage.lastTimeUsedMillis = max(usage.lastTimeUsedMillis, overlapEndMillis)
                    }
                }
            }
        }
        activeStarts.forEach { (packageName, activeStartMillis) ->
            val overlapStartMillis = max(activeStartMillis, startMillis)
            if (effectiveEndMillis > overlapStartMillis) {
                val usage = usageByPackage.getOrPut(packageName) { MutableUsageValues() }
                usage.totalTimeForegroundMillis += effectiveEndMillis - overlapStartMillis
                usage.lastTimeUsedMillis = max(usage.lastTimeUsedMillis, effectiveEndMillis)
            }
        }
        return usageByPackage.mapValues { (_, usage) ->
            UsageValues(
                totalTimeForegroundMillis = usage.totalTimeForegroundMillis,
                lastTimeUsedMillis = usage.lastTimeUsedMillis,
                launchCount = usage.launchCount,
            )
        }
    }

    private fun Int.isLaunchEvent(): Boolean {
        return isForegroundStartEvent()
    }

    private fun Int.isForegroundStartEvent(): Boolean {
        return this == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this == UsageEvents.Event.ACTIVITY_RESUMED)
    }

    private fun Int.isForegroundEndEvent(): Boolean {
        return this == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this == UsageEvents.Event.ACTIVITY_PAUSED) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this == UsageEvents.Event.ACTIVITY_STOPPED)
    }

    private fun UsageStatsPeriod.toWindowMillis(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = when (this) {
            UsageStatsPeriod.Today -> today
            UsageStatsPeriod.Yesterday -> today.minusDays(1)
            UsageStatsPeriod.Last7Days -> today.minusDays(6)
        }
        val endDate = when (this) {
            UsageStatsPeriod.Today -> today.plusDays(1)
            UsageStatsPeriod.Yesterday -> today
            UsageStatsPeriod.Last7Days -> today.plusDays(1)
        }
        return startDate.atStartOfDay(zone).toInstant().toEpochMilli() to
            endDate.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private data class UsageValues(
        val totalTimeForegroundMillis: Long,
        val lastTimeUsedMillis: Long,
        val launchCount: Int,
    )

    private data class MutableUsageValues(
        var totalTimeForegroundMillis: Long = 0L,
        var lastTimeUsedMillis: Long = 0L,
        var launchCount: Int = 0,
    )
}
