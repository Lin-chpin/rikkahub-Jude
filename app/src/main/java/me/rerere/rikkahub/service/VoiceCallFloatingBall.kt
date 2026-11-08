package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import me.rerere.rikkahub.RouteActivity

private const val TAG = "VoiceCallFlow"

internal object VoiceCallFloatingBallActions {
    enum class Action { RETURN_TO_CALL, CAPTURE_AND_SEND }
    val events = MutableSharedFlow<Action>(extraBufferCapacity = 1)
}

/** 在屏幕共享服务中持有，App 切到后台时显示，返回前台时移除。 */
internal class VoiceCallFloatingBall(context: Context) : DefaultLifecycleObserver, AutoCloseable {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val lifecycle = ProcessLifecycleOwner.get().lifecycle
    private val diameter = (56 * appContext.resources.displayMetrics.density).toInt()
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = (16 * appContext.resources.displayMetrics.density).toInt()
        y = (144 * appContext.resources.displayMetrics.density).toInt()
    }
    private var bubble: LinearLayout? = null
    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var thumbnailView: ImageView? = null
    private var thumbnailBytes: ByteArray? = null
    private var expanded = false
    private var status: String? = null
    private var temporarilyHidden = false
    private var closed = false

    init {
        lifecycle.addObserver(this)
        updateVisibility()
    }

    override fun onStart(owner: LifecycleOwner) = updateVisibility()
    override fun onStop(owner: LifecycleOwner) = updateVisibility()

    fun setStatus(value: String) {
        val nextStatus = voiceCallFloatingBallStatus(value)
        if (status != nextStatus) Log.i(TAG, "floating ball status changed value=${nextStatus ?: "none"}")
        status = nextStatus
        statusView?.apply {
            text = status.orEmpty()
            textSize = status?.let(::statusTextSize) ?: 12f
            contentDescription = status ?: "语音通话悬浮球"
        }
    }

    fun setVisualFeedback(value: String, thumbnail: ByteArray?) {
        Log.i(TAG, "floating ball feedback status=${value.ifBlank { "none" }} thumbnail=${thumbnail != null} bytes=${thumbnail?.size ?: 0}")
        setStatus(value)
        thumbnailBytes = thumbnail
        root?.let {
            renderThumbnail(it)
            renderActions(it)
        }
    }

    fun hideForCapture() {
        Log.i(TAG, "floating ball hide for capture")
        temporarilyHidden = true
        hide()
    }

    fun showAfterCapture() {
        Log.i(TAG, "floating ball restore after capture")
        temporarilyHidden = false
        updateVisibility()
    }

    private fun updateVisibility() {
        if (closed) return
        val appVisible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        val overlayPermission = Settings.canDrawOverlays(appContext)
        val shouldShow = !appVisible && !temporarilyHidden && overlayPermission
        if (shouldShow && bubble == null) {
            Log.i(TAG, "floating ball visibility show appVisible=$appVisible temporarilyHidden=$temporarilyHidden permission=$overlayPermission")
        } else if (!shouldShow && bubble != null) {
            Log.i(TAG, "floating ball visibility hide appVisible=$appVisible temporarilyHidden=$temporarilyHidden permission=$overlayPermission")
        }
        if (shouldShow) show() else hide()
    }

    private fun textBubble(
        label: String,
        onClick: () -> Unit,
        size: Float = 14f,
        hapticFeedback: Boolean = false,
    ) = TextView(appContext).apply {
        text = label
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        contentDescription = label
        isClickable = true
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(57, 72, 131))
            setStroke((2 * appContext.resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        elevation = 8 * appContext.resources.displayMetrics.density
        setOnClickListener {
            if (hapticFeedback) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    private fun show() {
        if (bubble != null) return
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val main = textBubble(status.orEmpty(), {
            expanded = !expanded
            renderActions(root)
        }, status?.let(::statusTextSize) ?: 12f).apply {
            layoutParams = LinearLayout.LayoutParams(diameter, diameter)
            contentDescription = status ?: "语音通话悬浮球"
        }
        statusView = main
        root.addView(main)
        this.root = root
        renderThumbnail(root)
        renderActions(root)

        val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        main.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) dragged = true
                    if (dragged) {
                        params.x = (startX - dx.toInt()).coerceIn(0, (appContext.resources.displayMetrics.widthPixels - diameter).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (appContext.resources.displayMetrics.heightPixels - diameter).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) touched.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        try {
            windowManager.addView(root, params)
            bubble = root
            Log.i(TAG, "floating ball window added thumbnail=${thumbnailView != null} expanded=$expanded")
        } catch (error: WindowManager.BadTokenException) {
            Log.w(TAG, "floating ball add failed exception=${error::class.simpleName ?: "Exception"}")
            // Overlay permission may be revoked while sharing; the call remains available from its notification.
        } catch (error: SecurityException) {
            Log.w(TAG, "floating ball add failed exception=${error::class.simpleName ?: "Exception"}")
            // Overlay permission may be revoked while sharing; the call remains available from its notification.
        }
    }

    private fun renderActions(root: LinearLayout) {
        val actionStart = if (thumbnailView == null) 1 else 2
        root.removeViews(actionStart, (root.childCount - actionStart).coerceAtLeast(0))
        if (!expanded) return
        val density = appContext.resources.displayMetrics.density
        val returnAction = textBubble("返回\n通话", onClick = { launchCall(); expanded = false; bubble?.let(::renderActions) })
        val captureAction = textBubble("截", onClick = {
            VoiceCallFloatingBallActions.events.tryEmit(VoiceCallFloatingBallActions.Action.CAPTURE_AND_SEND)
            expanded = false
            bubble?.let(::renderActions)
        }, hapticFeedback = true)
        listOf(returnAction, captureAction).forEachIndexed { index, view ->
            view.layoutParams = LinearLayout.LayoutParams(diameter, diameter).apply {
                topMargin = (8 * density).toInt()
            }
            view.contentDescription = if (index == 0) "返回通话" else "截图发送"
            (view as TextView).text = if (index == 0) "返回\n通话" else "截图\n发送"
            root.addView(view)
        }
    }

    private fun renderThumbnail(root: LinearLayout) {
        val bytes = thumbnailBytes
        if (bytes == null) {
            if (thumbnailView != null) Log.i(TAG, "floating ball thumbnail removed")
            thumbnailView?.let(root::removeView)
            thumbnailView = null
            return
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        Log.i(TAG, "floating ball thumbnail decode success=${bitmap != null} bytes=${bytes.size} size=${bitmap?.width}x${bitmap?.height}")
        if (thumbnailView != null) {
            thumbnailView?.setImageBitmap(bitmap)
            return
        }
        val density = appContext.resources.displayMetrics.density
        thumbnailView = ImageView(appContext).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "本轮截图预览"
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(Color.DKGRAY)
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(diameter, (diameter * 0.72f).toInt()).apply {
                topMargin = (6 * density).toInt()
            }
        }.also { root.addView(it, 1) }
    }

    private fun statusTextSize(value: String): Float = if (value.length >= 5) 10f else 12f

    private fun launchCall() {
        VoiceCallFloatingBallActions.events.tryEmit(VoiceCallFloatingBallActions.Action.RETURN_TO_CALL)
        appContext.startActivity(
            android.content.Intent(appContext, RouteActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun hide() {
        if (bubble != null) Log.i(TAG, "floating ball window removed")
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        root = null
        statusView = null
        thumbnailView = null
    }

    override fun close() {
        if (closed) return
        closed = true
        lifecycle.removeObserver(this)
        hide()
    }
}

internal fun voiceCallFloatingBallStatus(value: String): String? {
    val normalizedValue = value.lowercase()
    // Keep active visual work visible; after sending, the live reply replaces the completed visual status.
    val status = when {
        "发送失败" in value -> "发送失败"
        "正在截屏中" in value -> "截屏中"
        "正在识图中" in value -> "识图中"
        "正在识图并发送" in value || "正在发送" in value -> "发送中"
        listOf("鉴权失败", "权限错误", "api key", "unauthorized", "forbidden", "auth_error", "401", "403")
            .any { it in normalizedValue } -> "Key/权限错误"
        listOf("额度", "请求受限", "限流", "quota", "rate limit", "rate_limited", "commit_throttled", "queue_overflow", "402", "429")
            .any { it in normalizedValue } -> "额度/限流"
        listOf(
            "服务暂时不可用", "服务不可用", "服务端无法使用", "服务无法使用", "service unavailable",
            "server unavailable", "http 5", "500", "502", "503", "504",
        )
            .any { it in normalizedValue } -> "服务不可用"
        "ASR连接失败" in value -> "ASR失败"
        listOf("asr错误", "asr realtime error", "scribe error", "websocket")
            .any { it in normalizedValue } -> "ASR失败"
        "聆听已静音" in value -> "已静音"
        "AI 正在回复中" in value || "AI正在回复" in value -> "AI回复中"
        "已发送" in value -> "已发送"
        "正在连接 ASR" in value || "正在连接语音模型" in value -> "连接中"
        "已累计" in value || "已截图" in value -> "可以说话"
        "正在持续聆听" in value || "正在聆听" in value -> "正在聆听"
        "正在使用已配置的 ASR 识别" in value -> "识别中"
        "正在共享屏幕中" in value -> "共享屏幕中"
        else -> null
    }
    val count = Regex("""\d+/\d+""").find(value)?.value
    return listOfNotNull(status, count).joinToString("\n").takeIf { it.isNotBlank() }
}
