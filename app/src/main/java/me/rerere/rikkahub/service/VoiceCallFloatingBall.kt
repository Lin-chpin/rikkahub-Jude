package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import me.rerere.rikkahub.RouteActivity

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
    private var statusView: TextView? = null
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
        status = conciseStatus(value)
        statusView?.apply {
            text = status.orEmpty()
            textSize = status?.let(::statusTextSize) ?: 12f
            contentDescription = status ?: "语音通话悬浮球"
        }
    }

    fun hideForCapture() {
        temporarilyHidden = true
        hide()
    }

    fun showAfterCapture() {
        temporarilyHidden = false
        updateVisibility()
    }

    private fun updateVisibility() {
        if (closed) return
        val appVisible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (appVisible || temporarilyHidden || !Settings.canDrawOverlays(appContext)) hide() else show()
    }

    private fun textBubble(label: String, onClick: () -> Unit, size: Float = 14f) = TextView(appContext).apply {
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
        setOnClickListener { onClick() }
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
        } catch (_: WindowManager.BadTokenException) {
            // Overlay permission may be revoked while sharing; the call remains available from its notification.
        } catch (_: SecurityException) {
            // Overlay permission may be revoked while sharing; the call remains available from its notification.
        }
    }

    private fun renderActions(root: LinearLayout) {
        root.removeViews(1, (root.childCount - 1).coerceAtLeast(0))
        if (!expanded) return
        val density = appContext.resources.displayMetrics.density
        val returnAction = textBubble("返回\n通话", onClick = { launchCall(); expanded = false; bubble?.let(::renderActions) })
        val captureAction = textBubble("截", onClick = {
            VoiceCallFloatingBallActions.events.tryEmit(VoiceCallFloatingBallActions.Action.CAPTURE_AND_SEND)
            expanded = false
            bubble?.let(::renderActions)
        })
        listOf(returnAction, captureAction).forEachIndexed { index, view ->
            view.layoutParams = LinearLayout.LayoutParams(diameter, diameter).apply {
                topMargin = (8 * density).toInt()
            }
            view.contentDescription = if (index == 0) "返回通话" else "截图发送"
            (view as TextView).text = if (index == 0) "返回\n通话" else "截图\n发送"
            root.addView(view)
        }
    }

    private fun conciseStatus(value: String): String? = when {
        "AI 正在回复中" in value || "AI正在回复" in value -> "AI回复中"
        "正在持续聆听" in value || "正在聆听" in value -> "正在聆听"
        "聆听已静音" in value -> "已静音"
        "正在连接 ASR" in value || "正在连接语音模型" in value -> "连接中"
        "正在使用已配置的 ASR 识别" in value -> "识别中"
        else -> null
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
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        statusView = null
    }

    override fun close() {
        if (closed) return
        closed = true
        lifecycle.removeObserver(this)
        hide()
    }
}
