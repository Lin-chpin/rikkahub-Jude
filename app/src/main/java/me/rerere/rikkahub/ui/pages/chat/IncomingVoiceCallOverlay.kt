package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.dokar.sonner.ToastType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.data.ai.tools.REQUEST_VOICE_CALL_TOOL_NAME
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.data.voice.VOICE_CALL_UNAVAILABLE_MESSAGE
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import kotlin.math.roundToInt

internal data class IncomingVoiceCallRequest(
    val toolCallId: String,
    val assistantMessageId: String,
    val reason: String,
)

internal fun List<UIMessage>.pendingIncomingVoiceCall(): IncomingVoiceCallRequest? {
    val pendingMessage = asReversed()
        .asSequence()
        .firstOrNull { message ->
            message.parts
                .filterIsInstance<UIMessagePart.Tool>()
                .any { tool ->
                    tool.toolName == REQUEST_VOICE_CALL_TOOL_NAME && tool.isPending
                }
        } ?: return null
    val pendingTool = pendingMessage.parts
        .filterIsInstance<UIMessagePart.Tool>()
        .first { tool ->
            tool.toolName == REQUEST_VOICE_CALL_TOOL_NAME && tool.isPending
        }

    val input = pendingTool.inputAsJson() as? JsonObject
    val reason = input
        ?.get("reason")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        .orEmpty()

    return IncomingVoiceCallRequest(
        toolCallId = pendingTool.toolCallId,
        assistantMessageId = pendingMessage.id.toString(),
        reason = reason.ifBlank { "想和你聊聊" },
    )
}

@Composable
internal fun IncomingVoiceCallOverlay(
    request: IncomingVoiceCallRequest,
    setting: Settings,
    assistant: Assistant,
    userAvatar: Avatar,
    userName: String,
    assistantAvatar: Avatar,
    assistantName: String,
    onAccept: () -> Unit,
    onReject: (reason: String) -> Unit,
) {
    val ttsAvailable by LocalTTSState.current.isAvailable.collectAsState()
    val toaster = LocalToaster.current
    var resolved by remember(request.toolCallId) { mutableStateOf(false) }
    var secondsRemaining by remember(request.toolCallId) {
        mutableIntStateOf(INCOMING_CALL_TIMEOUT_SECONDS)
    }

    fun acceptOnce() {
        if (resolved) return
        if (!ttsAvailable) {
            resolved = true
            toaster.show(VOICE_CALL_UNAVAILABLE_MESSAGE, type = ToastType.Error)
            onReject(VOICE_CALL_UNAVAILABLE_MESSAGE)
            return
        }
        resolved = true
        onAccept()
    }

    fun rejectOnce(reason: String) {
        if (resolved) return
        resolved = true
        onReject(reason)
    }

    VibrateForIncomingCall(request.toolCallId)

    LaunchedEffect(request.toolCallId) {
        repeat(INCOMING_CALL_TIMEOUT_SECONDS) {
            delay(1_000)
            secondsRemaining--
        }
        rejectOnce("The voice call was not answered within 30 seconds.")
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                AssistantBackground(
                    setting = setting,
                    assistant = assistant,
                    modifier = Modifier.fillMaxSize(),
                    useVoiceCallBackground = true,
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = 28.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                Spacer(Modifier.weight(0.8f))

                IncomingCallAvatarPair(
                    userAvatar = userAvatar,
                    userName = userName,
                    assistantAvatar = assistantAvatar,
                    assistantName = assistantName,
                )

                Spacer(Modifier.height(28.dp))

                Text(
                    text = assistantName,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "正在邀请你进行语音通话",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = request.reason,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.weight(1f))

                Text(
                    text = "${secondsRemaining.coerceAtLeast(0)} 秒后视为未接",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                SwipeToAnswer(onAnswered = ::acceptOnce)

                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        rejectOnce("The user declined the voice call.")
                    },
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "拒接",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun IncomingCallAvatarPair(
    userAvatar: Avatar,
    userName: String,
    assistantAvatar: Avatar,
    assistantName: String,
) {
    Box(
        modifier = Modifier
            .width(206.dp)
            .height(112.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart)) {
            IncomingCallAvatar(name = userName, avatar = userAvatar)
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            IncomingCallAvatar(name = assistantName, avatar = assistantAvatar)
        }
    }
}

@Composable
private fun IncomingCallAvatar(
    name: String,
    avatar: Avatar,
) {
    Surface(
        modifier = Modifier.size(112.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Box(Modifier.padding(8.dp)) {
            UIAvatar(
                name = name,
                value = avatar,
                avatarSize = 96.dp,
            )
        }
    }
}

@Composable
private fun SwipeToAnswer(onAnswered: () -> Unit) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.CenterStart,
    ) {
        val knobSize = 64.dp
        val horizontalPadding = 4.dp
        val maximumOffset = with(density) {
            (maxWidth - knobSize - horizontalPadding * 2).toPx().coerceAtLeast(0f)
        }
        var dragOffset by remember(maximumOffset) { mutableFloatStateOf(0f) }
        val draggableState = rememberDraggableState { delta ->
            dragOffset = (dragOffset + delta).coerceIn(0f, maximumOffset)
        }

        Text(
            text = "滑动接听  →",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Box(
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .offset { IntOffset(dragOffset.roundToInt(), 0) }
                .size(knobSize)
                .clip(CircleShape)
                .background(ANSWER_GREEN)
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        if (maximumOffset > 0f && dragOffset >= maximumOffset * ANSWER_THRESHOLD) {
                            onAnswered()
                        } else {
                            animate(
                                initialValue = dragOffset,
                                targetValue = 0f,
                            ) { value, _ ->
                                dragOffset = value
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Voice,
                contentDescription = "滑动接听",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun VibrateForIncomingCall(callId: String) {
    val context = LocalContext.current

    DisposableEffect(context, callId) {
        val vibrator = context.incomingCallVibrator()
        if (vibrator?.hasVibrator() == true) {
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0L, 450L, 650L),
                intArrayOf(0, 180, 0),
                0,
            )
            vibrator.vibrate(effect)
        }

        onDispose {
            vibrator?.cancel()
        }
    }
}

@Suppress("DEPRECATION")
private fun Context.incomingCallVibrator(): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}

private const val INCOMING_CALL_TIMEOUT_SECONDS = 30
private const val ANSWER_THRESHOLD = 0.72f
private val ANSWER_GREEN = Color(0xFF2E9B5F)
