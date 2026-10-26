package me.rerere.rikkahub.data.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.VoiceCallScreenCaptureService
import java.util.UUID

enum class VoiceCallVisualSource { CAMERA, SCREEN }

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
        if (!closed) camera.setEnabled(enabled)
    }

    fun switchCamera() {
        if (!closed) camera.switchCamera()
    }

    fun createScreenCaptureIntent(): Intent =
        context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

    fun startScreenSharing(resultCode: Int, data: Intent) {
        if (closed || resultCode != Activity.RESULT_OK) return
        stopScreenSharing()
        val sessionId = UUID.randomUUID().toString()
        screenSessionId = sessionId
        VoiceCallScreenFrames.prepare(sessionId)
        runCatching { VoiceCallScreenCaptureService.start(context, sessionId, resultCode, data) }
            .onFailure {
                VoiceCallScreenFrames.stop(sessionId, "屏幕共享未能启动，请返回通话界面重新授权。")
            }
    }

    fun stopScreenSharing() {
        screenSessionId?.let { sessionId ->
            if (VoiceCallScreenFrames.stop(sessionId)) VoiceCallScreenCaptureService.stop(context)
        }
        screenSessionId = null
    }

    suspend fun captureFrames(): List<VoiceCallVisualFrame> {
        if (closed) return emptyList()
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
            return withContext(Dispatchers.Main.immediate) {
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
        } finally {
            mutableIsCapturing.value = false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        camera.close()
        stopScreenSharing()
    }
}
