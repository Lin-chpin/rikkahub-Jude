package me.rerere.rikkahub.personal.heartbeat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicLong

class SentinelObservationFailure(val code: String, val pause: Boolean) : Exception(code)

/** Only system-authorized screenshots and window ownership; no text or titles are inspected. */
class SentinelCaptureService : AccessibilityService() {
    override fun onServiceConnected() { connected = this; availability.value = ready() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { windowRevision.incrementAndGet() }
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (connected === this) { connected = null; availability.value = false }
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (connected === this) { connected = null; availability.value = false }
        super.onDestroy()
    }

    companion object {
        @Volatile private var connected: SentinelCaptureService? = null
        private val availability = MutableStateFlow(false)
        val available = availability.asStateFlow()
        private val windowRevision = AtomicLong()
        fun ready() = Build.VERSION.SDK_INT >= 30 && connected?.serviceInfo?.capabilities?.let {
            it and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0 &&
                it and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
        } == true

        private fun screenSnapshot(service: SentinelCaptureService): SentinelScreenSnapshot {
            val owners = runCatching {
                service.windows.filter { it.displayId == Display.DEFAULT_DISPLAY }.associate { window ->
                    val root = if (Build.VERSION.SDK_INT >= 33) window.getRoot(0) else window.root
                    try { window.id to root?.packageName?.toString() }
                    finally { @Suppress("DEPRECATION") root?.recycle() }
                }
            }.getOrDefault(emptyMap())
            return SentinelScreenSnapshot(windowRevision.get(), owners)
        }

        fun unavailableReason(context: Context): String? = when {
            !context.getSystemService(PowerManager::class.java).isInteractive -> "device_asleep"
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked -> "device_locked"
            else -> null
        }

        suspend fun capture(context: Context, blockedPackages: Set<String>): Bitmap = withContext(Dispatchers.Main.immediate) {
            unavailableReason(context)?.let { throw SentinelObservationFailure(it, false) }
            if (Build.VERSION.SDK_INT < 30) throw SentinelObservationFailure("android_version_unsupported", true)
            val service = connected ?: throw SentinelObservationFailure("accessibility_disconnected", true)
            val before = screenSnapshot(service)
            before.blockReason(blockedPackages)?.let { throw SentinelObservationFailure(it, false) }
            suspendCancellableCoroutine { continuation ->
                service.takeScreenshot(Display.DEFAULT_DISPLAY, context.mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bitmap = result.hardwareBuffer.use { buffer ->
                                unavailableReason(context)?.let { throw SentinelObservationFailure(it, false) }
                                val after = screenSnapshot(service)
                                after.blockReason(blockedPackages)?.let { throw SentinelObservationFailure(it, false) }
                                if (blockedPackages.isNotEmpty() && after.changedSince(before)) {
                                    throw SentinelObservationFailure("screen_changed", false)
                                }
                                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                try { hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle() }
                            } ?: throw SentinelObservationFailure("screenshot_empty", true)
                            continuation.resume(bitmap) { _, unusedBitmap, _ -> unusedBitmap.recycle() }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        val unavailable = unavailableReason(context)
                        val failure = when {
                            unavailable != null -> SentinelObservationFailure(unavailable, false)
                            errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> SentinelObservationFailure("secure_content", false)
                            errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> SentinelObservationFailure("capture_rate_limited", false)
                            errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> SentinelObservationFailure("accessibility_permission_missing", true)
                            else -> SentinelObservationFailure("screenshot_error_$errorCode", true)
                        }
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                })
            }
        }
    }
}
