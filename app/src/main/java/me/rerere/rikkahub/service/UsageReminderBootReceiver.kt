package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.java.KoinJavaComponent

private const val USAGE_REMINDER_BOOT_TAG = "UsageReminderBootReceiver"

class UsageReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                val settingsStore = KoinJavaComponent.get<SettingsStore>(SettingsStore::class.java)
                val settings = settingsStore.settingsFlowRaw.first()
                val hasActiveLock = settings.usageReminderState.activeLock
                    ?.takeIf { it.source == "ai_tool" }
                    ?.lockedUntilMillis
                    ?.let { it > System.currentTimeMillis() } == true
                if (settings.usageReminderConfig.rules.any { it.enabled } || hasActiveLock) {
                    UsageReminderService.sync(context.applicationContext, settings.usageReminderConfig)
                }
            }.onFailure {
                Log.e(USAGE_REMINDER_BOOT_TAG, "Failed to restore usage reminder monitor", it)
            }
            pendingResult.finish()
        }
    }
}
