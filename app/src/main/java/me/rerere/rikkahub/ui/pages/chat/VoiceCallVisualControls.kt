package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import me.rerere.rikkahub.data.voice.VOICE_CALL_VISUAL_MAX_IMAGES
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
        if (allowed && granted) capture.setCameraEnabled(true)
        else if (allowed) reportError("未获得摄像头权限，语音通话仍可继续。")
    }
    val screenPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (allowed && result.resultCode == Activity.RESULT_OK && result.data != null) {
            capture.startScreenSharing(result.resultCode, result.data!!)
        } else if (allowed) reportError("已取消屏幕共享，语音通话仍可继续。")
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!allowed) return@rememberLauncherForActivityResult
        if (Settings.canDrawOverlays(context)) {
            screenPermission.launch(capture.createScreenCaptureIntent())
        } else {
            reportError("请允许在其他应用上层显示，才能使用共享屏幕悬浮球。")
        }
    }
    LaunchedEffect(enabled, supportsImages) {
        if (!enabled || !supportsImages) {
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
                if (camera.enabled) capture.setCameraEnabled(false)
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }) { CallVisualLabel(if (camera.enabled) "关闭视频" else "视频") }
            CallVisualBubbleButton(Modifier.weight(1f), enabled && camera.enabled, onClick = capture::switchCamera) {
                CallVisualLabel("翻转镜头")
            }
            CallVisualBubbleButton(Modifier.weight(1f), enabled, onClick = {
                if (screen.active) capture.stopScreenSharing()
                else if (Settings.canDrawOverlays(context)) {
                    screenPermission.launch(capture.createScreenCaptureIntent())
                } else {
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
                    onClick = onCaptureAndSend,
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
