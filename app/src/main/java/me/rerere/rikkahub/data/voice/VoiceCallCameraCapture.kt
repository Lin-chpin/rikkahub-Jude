package me.rerere.rikkahub.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "VoiceCallFlow"

internal class VoiceCallCameraCapture(private val context: Context) : AutoCloseable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(VoiceCallCameraState())
    val state = mutableState.asStateFlow()
    private var view: TextureView? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var resumed = false
    private var ready = false
    private var opening = false
    private var displayListenerRegistered = false
    private var previewSize = Size(640, 480)
    private var sensorOrientation = 90
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (view?.display?.displayId == displayId) transform()
        }
    }
    var generation = 0L
        private set

    fun attach(view: TextureView) {
        if (this.view === view) return
        Log.i(TAG, "camera preview attach size=${view.width}x${view.height} available=${view.isAvailable}")
        detach()
        this.view = view
        displayManager.registerDisplayListener(displayListener, handler)
        displayListenerRegistered = true
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "camera surface available size=${width}x$height")
                open()
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "camera surface resized size=${width}x$height")
                transform()
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                Log.i(TAG, "camera surface destroyed ready=$ready generation=$generation")
                closeDevice()
                return true
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (session != null && !ready) {
                    ready = true
                    Log.i(TAG, "camera preview first_frame generation=$generation view=${view?.width}x${view?.height}")
                }
            }
        }
        open()
    }

    fun detach() {
        if (view != null) Log.i(TAG, "camera preview detach ready=$ready generation=$generation")
        closeDevice()
        if (displayListenerRegistered) {
            displayManager.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        view?.surfaceTextureListener = null
        view = null
    }

    fun updateTransform() = transform()

    fun setResumed(resumed: Boolean) {
        Log.i(TAG, "camera lifecycle resumed=$resumed previous=${this.resumed} enabled=${state.value.enabled}")
        this.resumed = resumed
        if (resumed) open() else closeDevice()
    }

    fun setEnabled(enabled: Boolean) {
        Log.i(TAG, "camera toggle enabled=$enabled previous=${state.value.enabled} front=${state.value.isFrontCamera}")
        mutableState.value = state.value.copy(enabled = enabled, error = null)
        if (enabled) open() else closeDevice()
    }

    fun switchCamera() {
        Log.i(TAG, "camera switch from=${if (state.value.isFrontCamera) "front" else "back"} to=${if (state.value.isFrontCamera) "back" else "front"}")
        closeDevice()
        mutableState.value = state.value.copy(isFrontCamera = !state.value.isFrontCamera, error = null)
        open()
    }

    fun snapshot(): Bitmap? {
        val reason = when {
            !state.value.enabled -> "disabled"
            !resumed -> "lifecycle_stopped"
            !ready -> "preview_not_ready"
            view == null -> "preview_missing"
            else -> null
        }
        if (reason != null) {
            Log.i(TAG, "camera snapshot unavailable reason=$reason generation=$generation")
            return null
        }
        val preview = view ?: return null
        val (width, height) = voiceCallFrameSize(preview.width, preview.height)
        val bitmap = preview.getBitmap(width, height)
        Log.i(TAG, "camera snapshot result=${bitmap != null} size=${width}x$height generation=$generation")
        return bitmap
    }

    fun canSnapshot(expectedGeneration: Long): Boolean =
        expectedGeneration == generation && state.value.enabled && resumed && ready

    @SuppressLint("MissingPermission")
    private fun open() {
        val preview = view ?: run {
            Log.i(TAG, "camera open skipped reason=preview_missing generation=$generation")
            return
        }
        val waitReason = when {
            !state.value.enabled -> "disabled"
            !resumed -> "lifecycle_stopped"
            !preview.isAvailable -> "surface_unavailable"
            opening -> "opening"
            device != null -> "already_open"
            else -> null
        }
        if (waitReason != null) {
            Log.i(TAG, "camera open skipped reason=$waitReason generation=$generation")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "camera open blocked reason=permission_denied")
            fail("未获得摄像头权限，语音通话仍可继续。")
            return
        }
        try {
            val facing = if (state.value.isFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
                else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            }
            if (cameraId == null) {
                Log.w(TAG, "camera open blocked reason=no_camera facing=${if (state.value.isFrontCamera) "front" else "back"}")
                fail("此设备没有可用的${if (state.value.isFrontCamera) "前置" else "后置"}摄像头。")
                return
            }
            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            previewSize = sizes.filter { maxOf(it.width, it.height) <= 1920 }
                .minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - 960L * 720L) }
                ?: sizes.minByOrNull { it.width.toLong() * it.height } ?: Size(640, 480)
            preview.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            transform()
            opening = true
            val attempt = ++generation
            Log.i(
                TAG,
                "camera open start facing=${if (state.value.isFrontCamera) "front" else "back"} " +
                    "sensor=$sensorOrientation stream=${previewSize.width}x${previewSize.height} " +
                    "view=${preview.width}x${preview.height} generation=$attempt",
            )
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (attempt != generation || !resumed || !state.value.enabled) {
                        Log.i(TAG, "camera open stale generation=$attempt current=$generation resumed=$resumed enabled=${state.value.enabled}")
                        camera.close()
                        return
                    }
                    opening = false
                    device = camera
                    Log.i(TAG, "camera opened generation=$attempt")
                    startPreview(camera, attempt)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    Log.w(TAG, "camera disconnected generation=$attempt current=$generation")
                    if (attempt == generation) fail("摄像头连接已中断，语音通话仍可继续。")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    Log.w(TAG, "camera device error=$error generation=$attempt current=$generation")
                    if (attempt == generation) fail("摄像头暂时不可用，可能正被其他应用占用。语音通话仍可继续。")
                }
            }, handler)
        } catch (error: Exception) {
            Log.w(TAG, "camera open exception=${error::class.simpleName ?: "Exception"}")
            fail("摄像头未能启动，请检查权限或稍后重试。语音通话仍可继续。")
        }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(camera: CameraDevice, attempt: Long) {
        val texture = view?.surfaceTexture ?: return
        val output = Surface(texture)
        surface = output
        try {
            camera.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (attempt != generation || device !== camera) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(output)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        configured.setRepeatingRequest(request.build(), null, handler)
                        Log.i(TAG, "camera preview configured generation=$attempt")
                    } catch (_: Exception) {
                        Log.w(TAG, "camera preview request failed generation=$attempt")
                        fail("摄像头预览未能启动，语音通话仍可继续。")
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    Log.w(TAG, "camera preview configure failed generation=$attempt current=$generation")
                    if (attempt == generation) fail("摄像头不支持当前预览，语音通话仍可继续。")
                }
            }, handler)
        } catch (_: Exception) {
            Log.w(TAG, "camera preview create session failed generation=$attempt")
            fail("摄像头预览未能启动，语音通话仍可继续。")
        }
    }

    private fun transform() {
        val preview = view ?: return
        if (preview.width <= 0 || preview.height <= 0) return
        val deviceRotation = when (preview.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val transform = voiceCallCameraPreviewTransform(
            viewWidth = preview.width,
            viewHeight = preview.height,
            previewWidth = previewSize.width,
            previewHeight = previewSize.height,
            sensorOrientationDegrees = sensorOrientation,
            displayRotationDegrees = deviceRotation,
            frontFacing = state.value.isFrontCamera,
        )
        val centerX = preview.width / 2f
        val centerY = preview.height / 2f
        val matrix = Matrix().apply {
            setScale(transform.scaleX, transform.scaleY, centerX, centerY)
            postRotate(transform.rotationDegrees, centerX, centerY)
            if (transform.mirrorHorizontally) postScale(-1f, 1f, centerX, centerY)
        }
        preview.setTransform(matrix)
        Log.i(
            TAG,
            "camera transform view=${preview.width}x${preview.height} stream=${previewSize.width}x${previewSize.height} " +
                "sensor=$sensorOrientation display=$deviceRotation rotation=${transform.rotationDegrees} " +
                "scale=${transform.scaleX},${transform.scaleY} mirror=${transform.mirrorHorizontally}",
        )
    }

    private fun fail(message: String) {
        Log.w(TAG, "camera failed generation=$generation front=${state.value.isFrontCamera}")
        closeDevice()
        mutableState.value = state.value.copy(enabled = false, error = message)
    }

    private fun closeDevice() {
        if (device != null || session != null || opening || ready) {
            Log.i(TAG, "camera close generation=$generation ready=$ready opening=$opening")
        }
        generation++
        ready = false
        opening = false
        session?.close()
        session = null
        device?.close()
        device = null
        surface?.release()
        surface = null
    }

    override fun close() {
        setEnabled(false)
        detach()
    }
}
