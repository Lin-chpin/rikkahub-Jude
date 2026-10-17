package me.rerere.rikkahub.ui.components.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.listener.control.IFxAppControl
import me.rerere.rikkahub.ui.theme.RikkahubTheme

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var window: IFxAppControl? by remember { mutableStateOf(null) }

    LaunchedEffect(visibility) {
        if (visibility) {
            window?.show()
        } else {
            window?.hide()
        }
    }

    DisposableEffect(context) {
        window = FloatingX.install {
            setTag(tag)
            setContext(context)
            setGravity(FxGravity.LEFT_OR_BOTTOM)
            setOffsetXY(20f, -20f)
            setEnableAnimation(true)
            setLayoutView(ComposeView(context).apply {
                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    val width = right - left
                    val height = bottom - top
                    if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                        Log.i("FloatingWindow", "layout tag=$tag widthPx=$width heightPx=$height")
                    }
                }
                setContent {
                    RikkahubTheme {
                        content()
                    }
                }
            })
        }
        if (visibility) window?.show() else window?.hide()
        onDispose {
            window?.cancel()
        }
    }
}
