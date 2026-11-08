package me.rerere.rikkahub.ui.pages.chat

import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.data.voice.VoiceCallVisualCapture

@Composable
internal fun VoiceCallCameraPreview(capture: VoiceCallVisualCapture, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember(context, capture) { TextureView(context) }
    AndroidView(
        factory = { preview },
        modifier = modifier.onSizeChanged { size ->
            Log.i("VoiceCallFlow", "camera preview UI size=${size.width}x${size.height}")
            capture.camera.updateTransform()
        },
    )
    DisposableEffect(capture, preview, owner) {
        val camera = capture.camera
        Log.i(
            "VoiceCallFlow",
            "camera preview UI mounted lifecycle=${owner.lifecycle.currentState} surface=${preview.isAvailable}",
        )
        camera.attach(preview)
        camera.setResumed(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    Log.i("VoiceCallFlow", "camera preview UI lifecycle=STARTED")
                    camera.setResumed(true)
                }
                Lifecycle.Event.ON_STOP -> {
                    Log.i("VoiceCallFlow", "camera preview UI lifecycle=STOPPED")
                    camera.setResumed(false)
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            Log.i("VoiceCallFlow", "camera preview UI disposed surface=${preview.isAvailable}")
            owner.lifecycle.removeObserver(observer)
            camera.setResumed(false)
            camera.detach()
        }
    }
}
