package me.rerere.usagetracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AppStore
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Time02
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UsageTrackerPage(
    onBack: () -> Unit,
    usageStatsToolEnabled: Boolean = false,
    usageReminderConfig: UsageReminderConfig = UsageReminderConfig(),
    usageReminderState: UsageReminderState = UsageReminderState(),
    onUsageStatsToolEnabledChange: (Boolean) -> Unit = {},
    onUsageReminderConfigChange: (UsageReminderConfig) -> Unit = {},
    onUsageReminderStateChange: (UsageReminderState) -> Unit = {},
) {
    val context = LocalContext.current
    val reader = remember(context) { UsageStatsReader(context) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedPeriod by remember { mutableStateOf(UsageStatsPeriod.Today) }
    var reminderMessagesImportResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val reminderMessagesImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val content = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: error("无法读取所选文件")
                        parseUsageReminderMessages(content)
                    }
                }
                result.onSuccess { messages ->
                    onUsageReminderConfigChange(
                        usageReminderConfig.copy(reminderMessages = messages)
                    )
                    reminderMessagesImportResult = if (messages.isEmpty()) {
                        "已清空提醒文案"
                    } else {
                        "已导入 ${messages.size} 条提醒文案"
                    }
                }.onFailure { error ->
                    reminderMessagesImportResult = "导入失败：${error.message ?: "JSON 格式不正确"}"
                }
            }
        }
    }
    val hasAccess by produceState(initialValue = reader.hasUsageAccess(), refreshKey) {
        value = reader.hasUsageAccess()
    }
    val hasNotificationPermission = remember(refreshKey) { context.hasNotificationPermission() }
    val hasOverlayPermission = remember(refreshKey) { context.canDrawOverlays() }
    val usages by produceState<List<AppUsageSummary>>(emptyList(), selectedPeriod, refreshKey, hasAccess) {
        value = if (hasAccess) reader.loadUsage(selectedPeriod) else emptyList()
    }
    val reminderState = usageReminderState.takeIf { it.date == todayKey() } ?: UsageReminderState(date = todayKey())
    val maxUsageMillis = usages.maxOfOrNull { it.totalTimeForegroundMillis } ?: 0L
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        refreshKey++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.usage_tracker_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.usage_tracker_back))
                    }
                },
                actions = {
                    TextButton(onClick = { refreshKey++ }) {
                        Text(stringResource(R.string.usage_tracker_refresh))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AiAccessCard(
                    enabled = usageStatsToolEnabled,
                    onEnabledChange = onUsageStatsToolEnabledChange,
                )
            }

            if (!hasAccess) {
                item {
                    PermissionCard(
                        onOpenSettings = {
                            reader.openUsageAccessSettings()
                        }
                    )
                }
            } else {
                item {
                    PeriodSelector(
                        selectedPeriod = selectedPeriod,
                        onSelected = {
                            selectedPeriod = it
                            refreshKey++
                        }
                    )
                }

                item {
                    SummaryCard(usages, selectedPeriod)
                }

                item {
                    ReminderIntroCard(
                        hasNotificationPermission = hasNotificationPermission,
                        onOpenNotificationSettings = { context.openNotificationSettings() },
                    )
                }
                item {
                    AiLockPermissionCard(
                        enabled = usageReminderConfig.lockEnabled,
                        hasOverlayPermission = hasOverlayPermission,
                        onOpenOverlaySettings = { context.openOverlayPermissionSettings() },
                        onEnabledChange = { enabled ->
                            onUsageReminderConfigChange(
                                usageReminderConfig.copy(lockEnabled = enabled)
                            )
                            if (!enabled) {
                                onUsageReminderStateChange(reminderState.copy(activeLock = null))
                            } else if (!hasOverlayPermission) {
                                context.openOverlayPermissionSettings()
                            }
                        },
                    )
                }
                item {
                    ReminderMessagesCard(
                        messageCount = usageReminderConfig.reminderMessages.size,
                        importResult = reminderMessagesImportResult,
                        onImport = {
                            reminderMessagesImportLauncher.launch(
                                arrayOf("application/json", "text/json")
                            )
                        },
                    )
                }

                if (usages.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.usage_tracker_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else {
                    items(usages, key = { it.packageName }) { usage ->
                        UsageListItem(
                            usage = usage,
                            maxUsageMillis = maxUsageMillis,
                            reminderRule = usageReminderConfig.rules.find { it.packageName == usage.packageName },
                            reminderState = reminderState.appStates[usage.packageName],
                            onReminderRuleChange = { rule ->
                                onUsageReminderConfigChange(usageReminderConfig.upsertRule(rule))
                            },
                            onClearIgnored = {
                                onUsageReminderStateChange(reminderState.clearIgnored(usage.packageName))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiLockPermissionCard(
    enabled: Boolean,
    hasOverlayPermission: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "AI 应用锁定工具",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (enabled) {
                        "开启后，助手才可以使用 usage_lock_control；关闭后会自动解开所有锁定。第三方应用锁定时自动退出并显示 10 秒中心提示，RikkaHub 只禁用聊天输入框。"
                    } else {
                        "开启后助手才会获得 usage_lock_control；关闭后不会提供 AI 应用锁定工具。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (enabled && !hasOverlayPermission) {
                    Text(
                        text = "悬浮窗权限未开启，第三方应用锁定只能发送通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onOpenOverlaySettings) {
                        Text("打开悬浮窗权限")
                    }
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

@Composable
private fun ReminderIntroCard(
    hasNotificationPermission: Boolean,
    onOpenNotificationSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "使用超限提醒",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "给某个应用打开提醒并设置今日累计阈值。超过后，每次再次打开都会提醒；第 3 次开始可以选择今日忽略。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hasNotificationPermission) {
                Text(
                    text = "通知权限未开启，提醒监控不会启动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onOpenNotificationSettings) {
                    Text("打开通知设置")
                }
            }
        }
    }
}

@Composable
private fun ReminderMessagesCard(
    messageCount: Int,
    importResult: String?,
    onImport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "提醒文案",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (messageCount == 0) {
                    "默认不包含提醒文案，通知只显示使用时长。"
                } else {
                    "已导入 $messageCount 条提醒文案，触发提醒时会随机显示一条。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "支持 JSON 字符串数组，或包含 message 字段的对象数组。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onImport) {
                Text("上传 JSON 文件")
            }
            importResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.startsWith("导入失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

@Composable
private fun AiAccessCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("允许当前助手读取使用情况")
                Text(
                    text = "开启后，连接的 AI 可以请求本机应用使用情况；每次实际调用前仍会弹出确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

@Composable
private fun PermissionCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.usage_tracker_permission_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.usage_tracker_permission_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.usage_tracker_permission_button))
            }
        }
    }
}

@Composable
private fun PeriodSelector(
    selectedPeriod: UsageStatsPeriod,
    onSelected: (UsageStatsPeriod) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UsageStatsPeriod.entries.forEach { period ->
            FilterChip(
                selected = selectedPeriod == period,
                onClick = { onSelected(period) },
                label = { Text(period.label()) },
            )
        }
    }
}

@Composable
private fun SummaryCard(
    usages: List<AppUsageSummary>,
    selectedPeriod: UsageStatsPeriod,
) {
    val totalMillis = usages.sumOf { it.totalTimeForegroundMillis }
    val launchCount = usages.sumOf { it.launchCount }
    val activeApps = usages.count { it.totalTimeForegroundMillis > 0L }
    val progress = (totalMillis.toFloat() / selectedPeriod.windowLengthMillis().toFloat()).coerceIn(0f, 1f)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.usage_tracker_overview),
                style = MaterialTheme.typography.titleMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageSummaryChip(
                    icon = HugeIcons.Time02,
                    text = stringResource(R.string.usage_tracker_total_time, formatDuration(totalMillis)),
                )
                UsageSummaryChip(
                    icon = HugeIcons.Rocket01,
                    text = stringResource(R.string.usage_tracker_launches, launchCount),
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
            Text(
                text = "已使用 $activeApps 个应用，占当前周期 ${"%.0f".format(progress * 100)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageSummaryChip(
    icon: ImageVector,
    text: String,
) {
    AssistChip(
        onClick = {},
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        label = { Text(text) },
    )
}

@Composable
private fun UsageListItem(
    usage: AppUsageSummary,
    maxUsageMillis: Long,
    reminderRule: UsageReminderRule?,
    reminderState: UsageReminderAppState?,
    onReminderRuleChange: (UsageReminderRule) -> Unit,
    onClearIgnored: () -> Unit,
) {
    val progress = if (maxUsageMillis <= 0L) {
        0f
    } else {
        (usage.totalTimeForegroundMillis.toFloat() / maxUsageMillis.toFloat()).coerceIn(0f, 1f)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(icon = usage.icon, label = usage.label)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = usage.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = usage.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatDuration(usage.totalTimeForegroundMillis),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = stringResource(R.string.usage_tracker_launches_short, usage.launchCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "使用占比 ${"%.0f".format(progress * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "最后使用：${formatLastUsed(usage.lastTimeUsedMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ReminderRuleControls(
                usage = usage,
                reminderRule = reminderRule,
                reminderState = reminderState,
                onReminderRuleChange = onReminderRuleChange,
                onClearIgnored = onClearIgnored,
            )
        }
    }
}

@Composable
private fun ReminderRuleControls(
    usage: AppUsageSummary,
    reminderRule: UsageReminderRule?,
    reminderState: UsageReminderAppState?,
    onReminderRuleChange: (UsageReminderRule) -> Unit,
    onClearIgnored: () -> Unit,
) {
    val enabled = reminderRule?.enabled == true
    val threshold = reminderRule?.thresholdMinutes ?: 60
    val ignored = reminderState?.ignored == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "超限提醒",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = if (ignored) {
                        "今日已忽略"
                    } else {
                        "今日累计超过 $threshold 分钟后提醒"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ignored) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    onReminderRuleChange(
                        UsageReminderRule(
                            packageName = usage.packageName,
                            label = usage.label,
                            thresholdMinutes = threshold,
                            enabled = checked,
                        )
                    )
                },
            )
        }
        if (enabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        onReminderRuleChange(
                            reminderRuleFor(usage, (threshold - 15).coerceAtLeast(1), true)
                        )
                    }
                ) {
                    Text("-15")
                }
                Text(
                    text = "$threshold 分钟",
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedButton(
                    onClick = {
                        onReminderRuleChange(
                            reminderRuleFor(usage, (threshold + 15).coerceAtMost(24 * 60), true)
                        )
                    }
                ) {
                    Text("+15")
                }
                if (ignored) {
                    TextButton(onClick = onClearIgnored) {
                        Text("取消忽略")
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(
    icon: Drawable?,
    label: String,
) {
    Surface(
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        if (icon != null) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageDrawable(icon)
                    }
                },
                update = { imageView ->
                    imageView.setImageDrawable(icon)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
                    .clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.AppStore,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun UsageStatsPeriod.label(): String {
    return when (this) {
        UsageStatsPeriod.Today -> stringResource(R.string.usage_tracker_today)
        UsageStatsPeriod.Yesterday -> stringResource(R.string.usage_tracker_yesterday)
        UsageStatsPeriod.Last7Days -> stringResource(R.string.usage_tracker_last_7_days)
    }
}

private fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}小时 ${minutes}分钟"
        minutes > 0 -> "${minutes}分钟"
        millis > 0 -> "<1分钟"
        else -> "0分钟"
    }
}

private fun UsageStatsPeriod.windowLengthMillis(): Long {
    val day = 24L * 60L * 60L * 1000L
    return when (this) {
        UsageStatsPeriod.Today,
        UsageStatsPeriod.Yesterday -> day
        UsageStatsPeriod.Last7Days -> day * 7L
    }
}

@Composable
private fun formatLastUsed(millis: Long): String {
    if (millis <= 0L) return stringResource(R.string.usage_tracker_never)
    return DateFormat.getDateFormat(LocalContext.current).format(Date(millis)) + " " +
        DateFormat.getTimeFormat(LocalContext.current).format(Date(millis))
}

private fun reminderRuleFor(
    usage: AppUsageSummary,
    thresholdMinutes: Int,
    enabled: Boolean,
): UsageReminderRule {
    return UsageReminderRule(
        packageName = usage.packageName,
        label = usage.label,
        thresholdMinutes = thresholdMinutes,
        enabled = enabled,
    )
}

private fun UsageReminderConfig.upsertRule(rule: UsageReminderRule): UsageReminderConfig {
    val nextRules = rules
        .filterNot { it.packageName == rule.packageName }
        .let { currentRules ->
            if (rule.enabled) {
                currentRules + rule
            } else {
                currentRules + rule.copy(enabled = false)
            }
        }
        .sortedBy { it.label.lowercase() }
    return copy(rules = nextRules)
}

private fun UsageReminderState.clearIgnored(packageName: String): UsageReminderState {
    val nextStates = appStates.toMutableMap()
    val current = nextStates[packageName] ?: UsageReminderAppState()
    nextStates[packageName] = current.copy(ignored = false)
    return copy(appStates = nextStates)
}

private fun Context.hasNotificationPermission(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

private fun Context.canDrawOverlays(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
}

private fun Context.openNotificationSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
    }
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun Context.openOverlayPermissionSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
    }
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
