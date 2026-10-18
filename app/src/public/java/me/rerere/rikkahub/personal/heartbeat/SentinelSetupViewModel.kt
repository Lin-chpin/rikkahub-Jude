package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.usagetracker.UsageStatsReader
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.responseMetadata

data class SentinelVerification(
    val running: Boolean = false, val key: String = "", val failed: Boolean = false,
    val testedKey: String = "", val completedAttempt: Int = 0,
    val failureReason: HeartbeatRunReason? = null,
    val response: UIMessage? = null,
)

class SentinelSetupViewModel : ViewModel() {
    private val result = MutableStateFlow(SentinelVerification())
    val verification = result.asStateFlow()
    private val appLabels = MutableStateFlow<Map<String, String>?>(null)
    val installedApps = appLabels.asStateFlow()
    private val appLoadError = MutableStateFlow(false)
    val appListFailed = appLoadError.asStateFlow()

    suspend fun loadAppIcon(context: Context, packageName: String) =
        UsageStatsReader(context.applicationContext).loadAppIcon(packageName)

    fun loadApps(context: Context) {
        if (appLabels.value != null) return
        appLoadError.value = false
        viewModelScope.launch {
            try { appLabels.value = UsageStatsReader(context.applicationContext).loadInstalledAppLabels() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                appLoadError.value = true
                Log.w("Sentinel", "app list unavailable error=${error::class.simpleName}")
            }
        }
    }

    fun verify(context: Context, selection: SentinelVisionModel, timeoutSeconds: Int) {
        if (result.value.running) return
        val attempt = result.value.completedAttempt + 1
        result.value = SentinelVerification(running = true, testedKey = selection.key, completedAttempt = attempt - 1)
        viewModelScope.launch(Dispatchers.IO) {
            var response: UIMessage? = null
            try {
                val passed = withTimeout(timeoutSeconds.coerceIn(5, 120) * 1_000L) {
                    SentinelVision.verify(context.applicationContext, selection) { response = it.responseMetadata() }
                }
                result.value = SentinelVerification(key = selection.key.takeIf { passed }.orEmpty(), failed = !passed,
                    testedKey = selection.key, completedAttempt = attempt, response = response)
                Log.i("Sentinel", "vision verification passed=$passed")
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                val reason = if (error is kotlinx.coroutines.TimeoutCancellationException) HeartbeatRunReason.NETWORK_TIMEOUT else HeartbeatFailureClassifier.classify(error)
                result.value = SentinelVerification(failed = true, testedKey = selection.key, completedAttempt = attempt, failureReason = reason)
                Log.w("Sentinel", "vision verification failed reason=$reason error=${error::class.simpleName}")
            }
        }
    }
}
