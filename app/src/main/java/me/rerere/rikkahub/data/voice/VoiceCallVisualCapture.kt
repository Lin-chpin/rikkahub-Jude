package me.rerere.rikkahub.data.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.VoiceCallScreenCaptureService
import java.util.UUID

private const val TAG = "VoiceCallFlow"

enum class VoiceCallVisualSource { CAMERA, SCREEN }

internal fun voiceCallVisualSourceBlocked(
    requested: VoiceCallVisualSource,
    cameraEnabled: Boolean,
    screenActive: Boolean,
): Boolean = when (requested) {
    VoiceCallVisualSource.CAMERA -> screenActive
    VoiceCallVisualSource.SCREEN -> cameraEnabled
}

data class VoiceCallVisualFrame(val source: VoiceCallVisualSource, val jpeg: ByteArray)

data class VoiceCallCameraState(
    val enabled: Boolean = false,
    val isFrontCamera: Boolean = true,
    val error: String? = null,
)

data class VoiceCallScreenState(val active: Boolean = false, val error: String? = null)

/** 通话专属临时采集；帧只供本次发言使用，不写入消息或文件。 */
class VoiceCallVisualCapture(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    internal val camera = VoiceCallCameraCapture(this.context)
    private var screenSessionId: String? = null
    private var closed = false
    private val mutableIsCapturing = MutableStateFlow(false)
    val cameraState = camera.state
    val screenState = VoiceCallScreenFrames.state
    val isCapturing = mutableIsCapturing.asStateFlow()

    fun setCameraEnabled(enabled: Boolean) {
        Log.i(TAG, "camera toggle requested enabled=$enabled closed=$closed")
        if (closed) return
        if (enabled && voiceCallVisualSourceBlocked(
                VoiceCallVisualSource.CAMERA,
                cameraEnabled = cameraState.value.enabled,
                screenActive = screenState.value.active,
            )
        ) {
            Log.i(TAG, "camera toggle rejected reason=screen_sharing_active")
            return
        }
        camera.setEnabled(enabled)
    }

    fun switchCamera() {
        Log.i(TAG, "camera switch requested closed=$closed")
        if (!closed) camera.switchCamera()
    }

    fun createScreenCaptureIntent(): Intent =
        context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

    fun startScreenSharing(resultCode: Int, data: Intent) {
        if (closed || resultCode != Activity.RESULT_OK) {
            Log.i(TAG, "screen share start ignored closed=$closed resultOk=${resultCode == Activity.RESULT_OK}")
            return
        }
        if (voiceCallVisualSourceBlocked(
                VoiceCallVisualSource.SCREEN,
                cameraEnabled = cameraState.value.enabled,
                screenActive = screenState.value.active,
            )
        ) {
            Log.i(TAG, "screen share start rejected reason=camera_active")
            return
        }
        stopScreenSharing()
        val sessionId = UUID.randomUUID().toString()
        screenSessionId = sessionId
        Log.i(TAG, "screen share start requested session=${sessionId.take(8)}")
        VoiceCallScreenFrames.prepare(sessionId)
        runCatching { VoiceCallScreenCaptureService.start(context, sessionId, resultCode, data) }
            .onFailure {
                Log.w(TAG, "screen share service start failed session=${sessionId.take(8)} exception=${it::class.simpleName ?: "Exception"}")
                VoiceCallScreenFrames.stop(sessionId, "屏幕共享未能启动，请返回通话界面重新授权。")
            }
    }

    fun stopScreenSharing() {
        val sessionId = screenSessionId
        if (sessionId != null) {
            val owned = VoiceCallScreenFrames.stop(sessionId)
            Log.i(TAG, "screen share stop requested session=${sessionId.take(8)} owned=$owned")
            if (owned) VoiceCallScreenCaptureService.stop(context)
        }
        screenSessionId = null
    }

    suspend fun captureFrames(): List<VoiceCallVisualFrame> {
        if (closed) {
            Log.i(TAG, "visual capture skipped reason=closed")
            return emptyList()
        }
        val cameraBefore = cameraState.value
        val screenBefore = screenState.value
        Log.i(
            TAG,
            "visual capture start cameraEnabled=${cameraBefore.enabled} front=${cameraBefore.isFrontCamera} " +
                "screenActive=${screenBefore.active} screenSession=${screenSessionId?.take(8) ?: "none"}",
        )
        mutableIsCapturing.value = true
        try {
            val (cameraGeneration, bitmap) = withContext(Dispatchers.Main.immediate) {
                camera.generation to camera.snapshot()
            }
            val cameraFrame = bitmap?.let {
                try {
                    withContext(Dispatchers.Default) { encodeVoiceCallFrame(it) }
                } finally {
                    it.recycle()
                }
            }
            val screenOwner = screenSessionId
            val screenCaptureStartedAt = SystemClock.elapsedRealtime()
            val screenFrame = if (screenOwner != null && screenState.value.active) {
                try {
                    withContext(Dispatchers.Main.immediate) {
                        VoiceCallScreenCaptureService.setFloatingBallHidden(true)
                    }
                    val frameVersion = VoiceCallScreenFrames.clearFrame(screenOwner)
                    val freshFrame = VoiceCallScreenFrames.snapshotAfterFrame(screenOwner, frameVersion)
                    freshFrame
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        VoiceCallScreenCaptureService.setFloatingBallHidden(false)
                    }
                }
            } else {
                null
            }
            if (screenOwner != null && screenBefore.active) {
                Log.i(
                    TAG,
                    "screen snapshot result=${screenFrame != null} bytes=${screenFrame?.size ?: 0} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - screenCaptureStartedAt} session=${screenOwner.take(8)}",
                )
            }
            val frames = withContext(Dispatchers.Main.immediate) {
                buildList {
                    if (!closed && camera.canSnapshot(cameraGeneration) && cameraFrame != null) {
                        add(VoiceCallVisualFrame(VoiceCallVisualSource.CAMERA, cameraFrame))
                    }
                    if (!closed && screenOwner != null && screenSessionId == screenOwner &&
                        VoiceCallScreenFrames.owns(screenOwner) && screenState.value.active && screenFrame != null) {
                        add(VoiceCallVisualFrame(VoiceCallVisualSource.SCREEN, screenFrame))
                    }
                }
            }
            Log.i(
                TAG,
                "visual capture complete sources=${frames.joinToString(",") { it.source.name }.ifBlank { "none" }} " +
                    "cameraBytes=${cameraFrame?.size ?: 0} screenActive=${screenBefore.active} " +
                    "screenBytes=${screenFrame?.size ?: 0}",
            )
            return frames
        } finally {
            mutableIsCapturing.value = false
        }
    }

    override fun close() {
        if (closed) return
        Log.i(TAG, "visual capture close cameraEnabled=${cameraState.value.enabled} screenSession=${screenSessionId?.take(8) ?: "none"}")
        closed = true
        camera.close()
        stopScreenSharing()
    }
}
