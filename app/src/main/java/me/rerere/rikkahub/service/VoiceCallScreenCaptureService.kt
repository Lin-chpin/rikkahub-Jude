package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.voice.VoiceCallScreenFrames
import me.rerere.rikkahub.data.voice.voiceCallFrameSize

class VoiceCallScreenCaptureService : Service() {
    private var floatingBall: VoiceCallFloatingBall? = null
    private var owner: String? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var currentSize: Pair<Int, Int>? = null
    private val captureThread = HandlerThread("VoiceCallScreenCapture")
    private lateinit var captureHandler: Handler
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private var projectionCallback: MediaProjection.Callback? = null
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopForScreenLock()
        }
    }

    private fun callbackFor(sessionId: String, source: MediaProjection) = object : MediaProjection.Callback() {
        private fun isCurrent() = owner == sessionId && projection === source

        override fun onStop() {
            if (!isCurrent()) return
            VoiceCallScreenFrames.stop(sessionId)
            releaseCapture()
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!isCurrent()) return
            runCatching { resize(width, height) }.onFailure { fail() }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (isCurrent()) VoiceCallScreenFrames.setVisible(sessionId, isVisible)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningService = this
        floatingBall = VoiceCallFloatingBall(this)
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (request.action == ACTION_STOP) {
            owner?.let { VoiceCallScreenFrames.stop(it) }
            releaseCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        val sessionId = request.getStringExtra(EXTRA_SESSION)
        if (sessionId == null || !VoiceCallScreenFrames.owns(sessionId)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive) {
            stopForScreenLock(sessionId)
            return START_NOT_STICKY
        }
        if (owner == sessionId && projection != null) return START_NOT_STICKY
        releaseCapture()
        owner = sessionId
        try {
            startCaptureForeground()
            val consent = if (Build.VERSION.SDK_INT >= 33) {
                request.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                request.getParcelableExtra<Intent>(EXTRA_CONSENT)
            } ?: error("Missing screen capture consent")
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mediaProjection = manager.getMediaProjection(request.getIntExtra(EXTRA_RESULT, 0), consent)
                ?: error("Screen capture permission was revoked")
            projection = mediaProjection
            // Android 14 要求每次授权只创建一个显示器，且必须先注册停止回调。
            val callback = callbackFor(sessionId, mediaProjection)
            projectionCallback = callback
            mediaProjection.registerCallback(callback, mainHandler)
            val (width, height) = screenDimensions()
            val (captureWidth, captureHeight) = voiceCallFrameSize(width, height)
            val imageReader = createReader(sessionId, captureWidth, captureHeight)
            reader = imageReader
            currentSize = captureWidth to captureHeight
            display = mediaProjection.createVirtualDisplay(
                "VoiceCallScreen", captureWidth, captureHeight, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, mainHandler,
            )
            VoiceCallScreenFrames.started(sessionId)
        } catch (_: Exception) {
            fail()
        }
        return START_NOT_STICKY
    }

    private fun createReader(sessionId: String, width: Int, height: Int): ImageReader {
        val generation = VoiceCallScreenFrames.newReader(sessionId)
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source ->
                runCatching { source.acquireLatestImage() }.getOrNull()?.let {
                    VoiceCallScreenFrames.offer(sessionId, generation, it)
                }
            }, captureHandler)
        }
    }

    private fun resize(width: Int, height: Int) {
        val currentDisplay = display ?: return
        val sessionId = owner ?: return
        if (width <= 0 || height <= 0) return
        val size = voiceCallFrameSize(width, height)
        if (size == currentSize) return
        VoiceCallScreenFrames.newReader(sessionId)
        currentDisplay.surface = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        val replacement = createReader(sessionId, size.first, size.second)
        reader = replacement
        currentDisplay.resize(size.first, size.second, resources.configuration.densityDpi)
        currentDisplay.surface = replacement.surface
        currentSize = size
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34) {
            val (width, height) = screenDimensions()
            runCatching { resize(width, height) }.onFailure { fail() }
        }
    }

    @Suppress("DEPRECATION")
    private fun screenDimensions(): Pair<Int, Int> {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            DisplayMetrics().apply { windowManager.defaultDisplay.getRealMetrics(this) }
                .let { it.widthPixels to it.heightPixels }
        }
    }

    private fun startCaptureForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "通话屏幕共享", NotificationManager.IMPORTANCE_LOW),
        )
        val stopIntent = PendingIntent.getService(
            this, NOTIFICATION_ID,
            Intent(this, VoiceCallScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("正在向 AI 共享屏幕")
            .setContentText("点击“截屏发送”可将当前画面发给 AI。")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(
                this, NOTIFICATION_ID, Intent(this, RouteActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .addAction(0, "停止共享", stopIntent)
            .build()
        var serviceType = 0
        if (Build.VERSION.SDK_INT >= 29) serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // 从可见通话页授权启动，使共享其他应用时既有 ASR 仍能使用麦克风。
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            serviceType,
        )
    }

    private fun fail() {
        owner?.let { VoiceCallScreenFrames.stop(it, "屏幕共享已中断，请返回通话界面重新授权。语音通话仍可继续。") }
        releaseCapture()
        stopSelf()
    }

    private fun stopForScreenLock(sessionId: String? = owner) {
        sessionId?.let { VoiceCallScreenFrames.stop(it, "屏幕关闭或锁定，共享已停止。解锁后可重新开启共享。") }
        releaseCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCapture() {
        val sessionId = owner
        owner = null
        sessionId?.let(VoiceCallScreenFrames::clearFrame)
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.close()
        reader = null
        currentSize = null
        val previous = projection
        val previousCallback = projectionCallback
        projection = null
        projectionCallback = null
        if (previousCallback != null) previous?.unregisterCallback(previousCallback)
        previous?.stop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        floatingBall?.close()
        floatingBall = null
        if (runningService === this) runningService = null
        owner?.let { VoiceCallScreenFrames.stop(it) }
        releaseCapture()
        captureThread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "voice_call_screen_capture"
        private const val NOTIFICATION_ID = 20_004
        private const val ACTION_STOP = "voice_call.stop_screen_capture"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_CONSENT = "consent"
        @Volatile
        private var runningService: VoiceCallScreenCaptureService? = null

        internal fun start(context: Context, sessionId: String, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceCallScreenCaptureService::class.java)
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_RESULT, resultCode)
                .putExtra(EXTRA_CONSENT, data))
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, VoiceCallScreenCaptureService::class.java))
        }

        internal fun setFloatingBallHidden(hidden: Boolean) {
            val service = runningService ?: return
            if (hidden) service.floatingBall?.hideForCapture()
            else service.floatingBall?.showAfterCapture()
        }

        internal fun setFloatingBallStatus(status: String) {
            runningService?.floatingBall?.setStatus(status)
        }

        internal fun refreshForegroundForMicrophone() {
            runningService?.takeIf { it.projection != null }?.startCaptureForeground()
        }
    }
}
