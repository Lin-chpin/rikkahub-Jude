package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.data.voice.VoiceCallVisualCapture
import me.rerere.rikkahub.data.voice.VoiceCallVisualRoundSnapshot
import me.rerere.rikkahub.data.voice.VoiceCallVisualRoundStatus
import me.rerere.rikkahub.data.voice.VoiceCallVisualSource
import me.rerere.rikkahub.data.voice.VOICE_CALL_VISUAL_MAX_IMAGES
import me.rerere.rikkahub.data.voice.voiceCallVisualSourceBlocked
import me.rerere.rikkahub.ui.components.ai.VoiceCallActionBubble

@Composable
internal fun VoiceCallVisualControls(
    capture: VoiceCallVisualCapture,
    enabled: Boolean,
    supportsImages: Boolean,
    visualRoundState: VoiceCallVisualRoundSnapshot,
    listening: Boolean,
    muted: Boolean,
    onCaptureAndSend: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val camera by capture.cameraState.collectAsState()
    val screen by capture.screenState.collectAsState()
    val allowed by rememberUpdatedState(enabled && supportsImages)
    val reportError by rememberUpdatedState(onError)
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i("VoiceCallFlow", "camera permission result granted=$granted allowed=$allowed")
        if (allowed && granted) {
            capture.setCameraEnabled(true)
            if (capture.screenState.value.active) reportError("视频和共享屏幕不能同时开启，请先停止共享屏幕。")
        } else if (allowed) reportError("未获得摄像头权限，语音通话仍可继续。")
    }
    val screenPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.i(
            "VoiceCallFlow",
            "screen permission result ok=${result.resultCode == Activity.RESULT_OK} hasData=${result.data != null} allowed=$allowed",
        )
        if (allowed && result.resultCode == Activity.RESULT_OK && result.data != null) {
            capture.startScreenSharing(result.resultCode, result.data!!)
            if (capture.cameraState.value.enabled) reportError("视频和共享屏幕不能同时开启，请先关闭视频。")
        } else if (allowed) reportError("已取消屏幕共享，语音通话仍可继续。")
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Log.i("VoiceCallFlow", "overlay permission result granted=${Settings.canDrawOverlays(context)} allowed=$allowed")
        if (!allowed) return@rememberLauncherForActivityResult
        if (Settings.canDrawOverlays(context)) {
            if (capture.cameraState.value.enabled) {
                reportError("视频和共享屏幕不能同时开启，请先关闭视频。")
            } else {
                screenPermission.launch(capture.createScreenCaptureIntent())
            }
        } else {
            reportError("请允许在其他应用上层显示，才能使用共享屏幕悬浮球。")
        }
    }
    LaunchedEffect(enabled, supportsImages) {
        if (!enabled || !supportsImages) {
            Log.i("VoiceCallFlow", "visual controls disabled featureEnabled=$enabled supportsImages=$supportsImages; stopping sources")
            capture.setCameraEnabled(false)
            capture.stopScreenSharing()
        }
    }
    LaunchedEffect(camera.error, screen.error, enabled, supportsImages) {
        if (enabled && supportsImages) (camera.error ?: screen.error)?.let(reportError)
    }
    if (!enabled || !supportsImages) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        VoiceCallVisualRoundFeedback(visualRoundState, listening, muted)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CallVisualBubbleButton(Modifier.weight(1f), enabled, onClick = {
                Log.i("VoiceCallFlow", "camera button clicked enabled=${camera.enabled} allowed=$allowed")
                if (camera.enabled) capture.setCameraEnabled(false)
                else if (voiceCallVisualSourceBlocked(
                        VoiceCallVisualSource.CAMERA,
                        cameraEnabled = camera.enabled,
                        screenActive = screen.active,
                    )
                ) {
                    Log.i("VoiceCallFlow", "camera enable rejected reason=screen_sharing_active")
                    reportError("视频和共享屏幕不能同时开启，请先停止共享屏幕。")
                } else {
                    Log.i("VoiceCallFlow", "camera permission requested")
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }) { CallVisualLabel(if (camera.enabled) "关闭视频" else "视频") }
            CallVisualBubbleButton(Modifier.weight(1f), enabled && camera.enabled, onClick = {
                Log.i("VoiceCallFlow", "camera switch button clicked front=${camera.isFrontCamera}")
                capture.switchCamera()
            }) {
                CallVisualLabel("翻转镜头")
            }
            CallVisualBubbleButton(Modifier.weight(1f), enabled, onClick = {
                Log.i("VoiceCallFlow", "screen share button clicked active=${screen.active} allowed=$allowed")
                if (screen.active) capture.stopScreenSharing()
                else if (voiceCallVisualSourceBlocked(
                        VoiceCallVisualSource.SCREEN,
                        cameraEnabled = camera.enabled,
                        screenActive = screen.active,
                    )
                ) {
                    Log.i("VoiceCallFlow", "screen share enable rejected reason=camera_active")
                    reportError("视频和共享屏幕不能同时开启，请先关闭视频。")
                } else if (Settings.canDrawOverlays(context)) {
                    Log.i("VoiceCallFlow", "screen capture permission requested overlayPermission=true")
                    screenPermission.launch(capture.createScreenCaptureIntent())
                } else {
                    Log.i("VoiceCallFlow", "overlay permission requested")
                    overlayPermission.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }
            }) { CallVisualLabel(if (screen.active) "停止共享" else "共享屏幕") }
            if (camera.enabled || screen.active) {
                val captureLabel = when {
                    visualRoundState.completed || visualRoundState.capturedImageCount == 0 -> "截图"
                    visualRoundState.capturedImageCount >= VOICE_CALL_VISUAL_MAX_IMAGES -> "已达${VOICE_CALL_VISUAL_MAX_IMAGES}张"
                    else -> "继续截图 ${visualRoundState.capturedImageCount}/$VOICE_CALL_VISUAL_MAX_IMAGES"
                }
                CallVisualBubbleButton(
                    Modifier.weight(1f),
                    enabled && visualRoundState.canAddScreenshot,
                    onClick = {
                        Log.i(
                            "VoiceCallFlow",
                            "screenshot button clicked camera=${camera.enabled} screen=${screen.active} " +
                                "round=${visualRoundState.status} captured=${visualRoundState.capturedImageCount}",
                        )
                        onCaptureAndSend()
                    },
                ) {
                    CallVisualLabel(captureLabel)
                }
            }
        }
    }
}

@Composable
private fun VoiceCallVisualRoundFeedback(
    state: VoiceCallVisualRoundSnapshot,
    listening: Boolean,
    muted: Boolean,
) {
    if (state.status == null) return
    val statusText = when (state.status) {
        VoiceCallVisualRoundStatus.CAPTURING -> "正在截图，已累计${state.capturedImageCount}/${VOICE_CALL_VISUAL_MAX_IMAGES}张"
        VoiceCallVisualRoundStatus.READY -> "已累计${state.capturedImageCount}/${VOICE_CALL_VISUAL_MAX_IMAGES}张，5秒无语音自动提交"
        VoiceCallVisualRoundStatus.SENDING -> "正在等待识图完成并发送"
        VoiceCallVisualRoundStatus.SENT -> "已发送"
        VoiceCallVisualRoundStatus.FAILED -> state.failureMessage ?: "识图或发送失败；可以重新截图。"
    }
    val progress = buildList {
        if (state.isRecognizing) add("正在识图 ${state.recognizedImageCount + state.failedImageCount}/${state.capturedImageCount}")
        if (state.failedImageCount > 0) add("${state.failedImageCount}张识图失败")
        when {
            listening -> add("正在聆听")
            muted -> add("已静音")
        }
    }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress.isNotBlank()) {
                Text(progress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CallVisualBubbleButton(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    VoiceCallActionBubble(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun CallVisualLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
}
