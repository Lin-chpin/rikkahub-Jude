package me.rerere.rikkahub.ui.pages.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.Locale
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.voice.VoiceCallCompletion
import me.rerere.rikkahub.data.voice.VoiceCallVisualCapture
import me.rerere.rikkahub.data.voice.VoiceCallVisualRoundState
import me.rerere.rikkahub.data.voice.VoiceCallVisualRoundStatus
import me.rerere.rikkahub.data.voice.VoiceCallVisualSendStatus
import me.rerere.rikkahub.data.voice.supportsVoiceCallImages
import me.rerere.rikkahub.data.voice.canUseVoiceCallVisuals
import me.rerere.rikkahub.data.voice.prepareRequest
import me.rerere.rikkahub.data.voice.hasConfiguredVoiceCallOcr
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagMode
import me.rerere.rikkahub.data.voice.forVoiceCallProvider
import me.rerere.rikkahub.data.voice.voiceCallRecord
import me.rerere.rikkahub.data.voice.VOICE_CALL_UNAVAILABLE_MESSAGE
import me.rerere.rikkahub.data.voice.voiceCallAudioTagFormatOrNull
import me.rerere.rikkahub.data.voice.voiceCallDisplayTextOrPlainText
import me.rerere.rikkahub.data.voice.withoutVoiceCallRealtimeEmotionMarker
import me.rerere.rikkahub.data.voice.sanitizeVoiceCallTextForTranslation
import me.rerere.rikkahub.service.ChatRequestMode
import me.rerere.rikkahub.service.VoiceCallSessionRegistry
import me.rerere.rikkahub.service.VoiceCallFloatingBallActions
import me.rerere.rikkahub.service.VoiceCallScreenCaptureService
import me.rerere.rikkahub.ui.components.richtext.appendVoiceCallAudioTagAwareText
import me.rerere.rikkahub.ui.components.ai.AsrButton
import me.rerere.rikkahub.ui.components.message.CollapsibleTranslationText
import me.rerere.rikkahub.ui.components.message.TranslateMessageButton
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.components.message.ChatMessageBubbleShape
import me.rerere.rikkahub.ui.components.message.MessageBubbleSurface
import me.rerere.rikkahub.ui.components.ai.VoiceCallActionBubble
import me.rerere.rikkahub.ui.theme.LocalLiquidGlass
import me.rerere.rikkahub.ui.theme.LocalLiquidGlassHaze
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.isElevenLabsV4
import me.rerere.tts.provider.isElevenLabsV4Family
import org.koin.compose.koinInject

private const val VOICE_CALL_VISUAL_DEFAULT_QUESTION = "请看一下我发送的当前画面。"

@Composable
fun VoiceCallOverlay(
    visible: Boolean,
    historyCallId: String? = null,
    awaitInitialAssistantReply: Boolean = false,
    initialAssistantMessageId: String? = null,
    initialVoiceCallToolCallId: String? = null,
    conversation: Conversation,
    userAvatar: Avatar,
    userName: String,
    assistantAvatar: Avatar,
    assistantName: String,
    loadingJob: Job?,
    hasChatModel: Boolean,
    vm: ChatVM,
    onDismiss: () -> Unit,
    onVoiceCallClosed: suspend (failureMessage: String?, completion: VoiceCallCompletion?) -> Unit = { _, _ -> },
    onMessageSubmitted: () -> Unit,
) {
    if (!visible) return

    val isHistory = historyCallId != null
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    }
    val routeActivity = context as? RouteActivity
    val filesManager: FilesManager = koinInject()
    val density = LocalDensity.current
    val keyboardFocusRequester = remember { FocusRequester() }
    val tts = LocalTTSState.current
    val toaster = LocalToaster.current
    val settings = LocalSettings.current
    val callAssistant = settings.getAssistantById(conversation.assistantId)
        ?: settings.getCurrentAssistant()
    val ttsAvailable by tts.isAvailable.collectAsState()
    val ttsProviderReady by tts.isProviderReady.collectAsState()
    val ttsError by tts.error.collectAsState()
    val playbackState by tts.playbackState.collectAsState()
    val selectedTtsProvider = settings.getSelectedTTSProvider()
    val voiceCallAudioTagFormat = selectedTtsProvider?.voiceCallAudioTagFormatOrNull()
    val voiceCallAudioTagMode = settings.voiceCallAudioTagMode.forVoiceCallProvider(selectedTtsProvider)
    val showVoiceCallTags = !isHistory &&
        voiceCallAudioTagMode != VoiceCallAudioTagMode.DISABLED &&
        voiceCallAudioTagFormat != null
    val showRealtimeVoiceCallTags = voiceCallAudioTagMode == VoiceCallAudioTagMode.REALTIME_MODEL &&
        voiceCallAudioTagFormat != null
    val fullDuplex = !isHistory && selectedTtsProvider is TTSProviderSetting.ElevenLabs &&
        selectedTtsProvider.isElevenLabsV4Family() && selectedTtsProvider.fullDuplex
    val useRealtimeSocketTts = selectedTtsProvider?.isElevenLabsV4Family() == true
    // v3 keeps one tagged HTTP request; both v4 models stream one reply over TTD WebSocket.
    val useWholeReplyTts = !useRealtimeSocketTts && selectedTtsProvider is TTSProviderSetting.ElevenLabs &&
        !selectedTtsProvider.isElevenLabsV4() && voiceCallAudioTagFormat != null
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    val callAsr = rememberCustomAsrState()
    val callAsrState by callAsr.state.collectAsState()
    val liveConversationJob by vm.conversationJob.collectAsState()

    val speechPlayback = rememberVoiceCallSpeechPlaybackState(awaitInitialAssistantReply)
    var keyboardCaptureActive by remember { mutableStateOf(false) }
    var keyboardShowRequest by remember { mutableStateOf(0) }
    var keyboardInputFieldKey by remember { mutableStateOf(0) }
    var keyboardInputSession by remember { mutableStateOf(0) }
    var submittedKeyboardInputSession by remember { mutableStateOf(0) }
    var keyboardInput by remember { mutableStateOf("") }
    var submittedKeyboardInput by remember { mutableStateOf("") }
    var submittingCallAsr by remember { mutableStateOf(false) }
    val reportedCallState = remember(initialVoiceCallToolCallId) { mutableStateOf(false) }
    var voiceCallResultReported by reportedCallState
    val callScope = rememberCoroutineScope()
    val visualCapture = remember(conversation.id) { VoiceCallVisualCapture(context.applicationContext) }
    val visualRound = remember(conversation.id) { VoiceCallVisualRoundState() }
    val visualRoundState by visualRound.state.collectAsState()
    var visualSpeechStarted by remember(conversation.id) { mutableStateOf(false) }
    val visualCameraState by visualCapture.cameraState.collectAsState()
    val visualScreenState by visualCapture.screenState.collectAsState()
    val capturingVisualFrame = visualRoundState.isCapturing
    val recognizingVisual = visualRoundState.isRecognizing
    var cameraPreviewExpanded by remember(conversation.id) { mutableStateOf(false) }
    val callModel = settings.findModelById(callAssistant.chatModelId ?: settings.chatModelId)
    val callModelSupportsImages = callModel?.supportsVoiceCallImages() == true
    val usesOcrFallback = remember(settings, callAssistant, callModelSupportsImages) {
        !callModelSupportsImages && settings.canUseVoiceCallVisuals(callAssistant)
    }
    val supportsCallImages = callModelSupportsImages || usesOcrFallback
    val visualFeatureEnabled by rememberUpdatedState(callAssistant.voiceCallVisualEnabled)
    val supportsCallImagesNow by rememberUpdatedState(supportsCallImages)
    val callModelSupportsImagesNow by rememberUpdatedState(callModelSupportsImages)
    val useSeparateVisualOcrNow by rememberUpdatedState(settings.hasConfiguredVoiceCallOcr())
    DisposableEffect(visualCapture) {
        onDispose {
            visualCapture.close()
        }
    }
    DisposableEffect(conversation.id) {
        onDispose {
            visualRound.clear()
        }
    }
    LaunchedEffect(isHistory, callAssistant.voiceCallVisualEnabled, supportsCallImages) {
        if (isHistory || !callAssistant.voiceCallVisualEnabled || !supportsCallImages) {
            visualCapture.setCameraEnabled(false)
            visualCapture.stopScreenSharing()
        }
    }
    LaunchedEffect(visualCameraState.enabled, visualScreenState.active) {
        if (!visualCameraState.enabled || visualScreenState.active) cameraPreviewExpanded = false
    }
    val stopDuplexInput = remember { arrayOf<() -> Unit>({}) }
    var showVoiceCallFlowDialog by remember { mutableStateOf(false) }
    val voiceCallFlowSteps = remember { mutableStateListOf<String>() }
    val callStartedAt = remember { System.currentTimeMillis() }
    val callId = remember { UUID.randomUUID().toString() }
    var hasSubmittedUserMessage by remember(callId) { mutableStateOf(false) }
    val callStartBaseline = remember(initialAssistantMessageId) {
        val messages = conversation.currentMessages
        val messageCount = initialAssistantMessageId
            ?.let { id -> messages.indexOfFirst { it.id.toString() == id } }
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: messages.size
        messages.take(messageCount).mapTo(mutableSetOf()) { it.id.toString() } to messageCount
    }
    val callStartMessageIds = callStartBaseline.first
    var callElapsedMillis by remember { mutableStateOf(0L) }
    val callStartMessageCount = callStartBaseline.second
    var voiceRequestStartMessageCount by remember { mutableStateOf(callStartMessageCount) }

    DisposableEffect(callId, isHistory) {
        if (!isHistory) {
            VoiceCallSessionRegistry.register(callId)
        }
        onDispose {
            if (!isHistory) {
                VoiceCallSessionRegistry.unregister(callId)
            }
        }
    }
    DisposableEffect(tts, callId, isHistory, useRealtimeSocketTts) {
        val prepareConnection = !isHistory && useRealtimeSocketTts
        if (prepareConnection) tts.setRealtimeDialoguePreconnectionEnabled(true)
        onDispose {
            if (prepareConnection) tts.setRealtimeDialoguePreconnectionEnabled(false)
        }
    }
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    fun recordVoiceCallFlow(step: String) {
        val elapsedMillis = (System.currentTimeMillis() - callStartedAt).coerceAtLeast(0L)
        Log.i("VoiceCallFlow", "[+${elapsedMillis}ms] $step")
        voiceCallFlowSteps += "[+" + elapsedMillis + "ms] " + step
        if (voiceCallFlowSteps.size > 300) {
            voiceCallFlowSteps.removeAt(0)
        }
    }

    val standaloneHistoryRecord = if (isHistory) {
        conversation.messageNodes.asSequence()
            .flatMap { it.messages.asSequence() }
            .mapNotNull { it.voiceCallRecord() }
            .firstOrNull { record -> record.standalone && record.callId == historyCallId }
    } else {
        null
    }
    val historyMessageIds = standaloneHistoryRecord?.messageIds.orEmpty()
    val visibleMessages = if (isHistory) {
        if (historyMessageIds.isNotEmpty()) {
            conversation.currentMessages.filter { message ->
                message.id.toString() in historyMessageIds &&
                    (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
            }
        } else {
            // Compatibility for records created before call data moved to the standalone node.
            conversation.currentMessages.filter { message ->
                message.voiceCallRecord()?.callId == historyCallId &&
                    (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
            }
        }
    } else {
        conversation.currentMessages.filter { message ->
            message.id.toString() !in callStartMessageIds &&
                (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
        }
    }
    val historyAudioSegmentsByMessageId = standaloneHistoryRecord?.audioSegmentsByMessageId.orEmpty()
    val historyDurationMillis = (standaloneHistoryRecord?.durationSeconds
        ?: visibleMessages.firstNotNullOfOrNull { it.voiceCallRecord()?.durationSeconds }
        ?: 0) * 1000L
    val messageListState = rememberLazyListState()
    // The incoming-call assistant/tool message is the baseline; only show the
    // new assistant reply produced after the call was accepted.
    val latestGeneratedAssistantMessage = conversation.currentMessages
        .drop(voiceRequestStartMessageCount)
        .lastOrNull { it.role == MessageRole.ASSISTANT }
    val currentAssistantMessage = latestGeneratedAssistantMessage
    val currentAssistantId = currentAssistantMessage?.id?.toString()
    val currentAssistantRawText = currentAssistantMessage?.toText().orEmpty()
    val currentAssistantText = if (showRealtimeVoiceCallTags) {
        currentAssistantRawText.withoutVoiceCallRealtimeEmotionMarker()
    } else {
        currentAssistantMessage?.voiceCallDisplayTextOrPlainText()
            ?.withoutVoiceCallRealtimeEmotionMarker()
            .orEmpty()
    }
    val pendingUserInputText = keyboardInput.trim()
    val pendingBubbleText = pendingUserInputText.ifBlank { submittedKeyboardInput }
        .ifBlank { callAsrState.transcript.takeIf { callAsrState.isRecording }.orEmpty() }
    LaunchedEffect(visibleMessages.size, currentAssistantText, pendingBubbleText) {
        val scrollTarget = if (pendingBubbleText.isNotBlank()) {
            visibleMessages.size
        } else {
            visibleMessages.lastIndex
        }
        if (scrollTarget >= 0) {
            messageListState.animateScrollToItem(scrollTarget)
        }
    }

    LaunchedEffect(Unit) {
        recordVoiceCallFlow(
            "通话页打开 history=" + isHistory + ", ttsAvailable=" + ttsAvailable +
                ", initialAssistant=" + (initialAssistantMessageId != null)
        )
        recordVoiceCallFlow(
            "TTS路径 provider=" + (selectedTtsProvider?.javaClass?.simpleName ?: "none") +
                ", mode=" + when {
                    useRealtimeSocketTts -> "realtime_dialogue"
                    useWholeReplyTts -> "whole_reply_http"
                    else -> "sentence_queue"
                } + ", tagMode=" + voiceCallAudioTagMode
        )
        recordVoiceCallFlow(
            "ASR配置 provider=" + (settings.getSelectedASRProvider()?.javaClass?.simpleName ?: "none") +
                ", available=" + callAsrState.isAvailable
        )
        if (isHistory) {
            val savedSegments = historyAudioSegmentsByMessageId.values.flatten().ifEmpty {
                visibleMessages.flatMap { it.voiceCallRecord()?.audioSegments.orEmpty() }
            }
            recordVoiceCallFlow(
                "打开通话记录 messages=" + visibleMessages.size +
                    ", savedAudioSegments=" + savedSegments.size
            )
            savedSegments.forEachIndexed { index, segment ->
                recordVoiceCallFlow(
                    "历史音频[" + index + "] format=" + segment.format + ", bytes=unknown"
                )
            }
        }
    }

    LaunchedEffect(ttsAvailable) {
        recordVoiceCallFlow("TTS可用状态=" + ttsAvailable)
    }

    LaunchedEffect(playbackState.status, playbackState.currentChunkIndex, playbackState.totalChunks) {
        recordVoiceCallFlow(
            "TTS播放状态 status=" + playbackState.status +
                ", chunk=" + playbackState.currentChunkIndex + "/" + playbackState.totalChunks +
                ", session=" + tts.playbackSessionId.value +
                ", hasError=" + (playbackState.errorMessage != null)
        )
    }

    LaunchedEffect(currentAssistantId, speechPlayback.replyPending, loadingJob) {
        recordVoiceCallFlow(
            "回复状态 messageId=" + (currentAssistantId ?: "none") +
                ", pending=" + speechPlayback.replyPending + ", loading=" + (loadingJob != null)
        )
    }

    LaunchedEffect(Unit) {
        if (isHistory) return@LaunchedEffect
        while (true) {
            callElapsedMillis = System.currentTimeMillis() - callStartedAt
            delay(1000)
        }
    }

    fun hangUp(failureMessage: String? = null) {
        if (voiceCallResultReported) return
        voiceCallResultReported = true
        visualCapture.close()
        stopDuplexInput[0]()
        callAsr.stop()
        val closingGeneration = vm.cancelGeneration()
        vm.stopGeneration()
        recordVoiceCallFlow(
            "挂断开始 failurePresent=" + (failureMessage != null) +
                ", audioSegments=" + speechPlayback.audioSegmentCount()
        )
        keyboardCaptureActive = false
        keyboardInput = ""
        submittedKeyboardInput = ""
        submittedKeyboardInputSession = keyboardInputSession
        keyboardController?.hide()
        tts.setRealtimeDialoguePreconnectionEnabled(false)
        tts.stop()
        speechPlayback.stopReply()
        callScope.launch {
            closingGeneration?.join()
            speechPlayback.awaitAudioSaves()
            val callMessages = vm.conversation.value.currentMessages.filter { message ->
                message.id.toString() !in callStartMessageIds &&
                    (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
            }
            val callMessageIds = callMessages.mapTo(mutableSetOf()) { it.id.toString() }
            val completion = VoiceCallCompletion(
                callId = callId,
                durationSeconds = ((callElapsedMillis + 999L) / 1000L).toInt().coerceAtLeast(1),
                messageIds = callMessageIds,
                audioSegmentsByMessageId = speechPlayback.audioSegmentsSnapshot().filterKeys { it in callMessageIds },
            )
            recordVoiceCallFlow(
                "通话记录完成 messages=" + completion.messageIds.size +
                    ", audioSegments=" + completion.audioSegmentsByMessageId.values.sumOf { it.size }
            )
            onVoiceCallClosed(failureMessage, completion)
            onDismiss()
        }
    }

    LaunchedEffect(visible, ttsProviderReady, ttsAvailable) {
        if (!isHistory && visible && ttsProviderReady && !ttsAvailable) {
            toaster.show(VOICE_CALL_UNAVAILABLE_MESSAGE, type = ToastType.Error)
        }
    }

    fun canStartInput(): Boolean {
        if (!hasChatModel) {
            toaster.show("请先选择聊天模型", type = ToastType.Error)
            return false
        }
        if (!ttsProviderReady) {
            toaster.show("语音模型正在初始化，请稍后再试", type = ToastType.Error)
            return false
        }
        if (!ttsAvailable) {
            toaster.show("请先到「设置 > 语音服务 > 文本转语音」选择语音模型", type = ToastType.Error)
            return false
        }
        if (!asrPermission.allRequiredPermissionsGranted) {
            asrPermission.requestPermissions()
            return false
        }
        return true
    }

    val aiReplyActive = speechPlayback.isReplyActive(
        loadingJob = liveConversationJob,
        playbackStatus = playbackState.status,
        currentAssistantId = currentAssistantId,
        currentAssistantTextLength = currentAssistantText.length,
    )

    fun interruptCurrentReplyForInput() {
        if (voiceCallResultReported) return
        if (aiReplyActive) visualRound.interruptSubmission()
        speechPlayback.interruptReply(currentAssistantId)
        vm.stopGeneration()
        tts.stop()
    }

    fun sendCapturedText(
        text: String,
        visualSessionId: String? = null,
    ): Boolean {
        if (voiceCallResultReported) return false
        val contentText = text.trim()
        if (contentText.isBlank()) {
            toaster.show("没有识别到语音", type = ToastType.Warning)
            return false
        }
        val activeVisualSessionId = visualSessionId?.takeIf(visualRound::isCurrent)
        val visualAttemptId = activeVisualSessionId?.let(visualRound::beginSubmission)
        if (activeVisualSessionId != null && visualAttemptId == null) return false
        if (speechPlayback.isReplyActive(loadingJob, playbackState.status, currentAssistantId, currentAssistantText.length)) {
            tts.stop()
        }
        voiceRequestStartMessageCount = conversation.currentMessages.size
        speechPlayback.beginReply(vm.voiceCallSpeech.value.generationJob)
        submittedKeyboardInput = contentText
        val includeConnectedEvent = !hasSubmittedUserMessage
        val visualPartsProvider: (suspend () -> List<UIMessagePart>)? = activeVisualSessionId?.let { sessionId ->
                {
                    val prepared = try {
                        visualRound.preparedRequest(sessionId)
                    } catch (error: CancellationException) {
                        throw error
                    }
                    if (prepared == null) {
                        emptyList()
                    } else {
                        withContext(Dispatchers.Main) {
                            if (prepared.hadActiveSource && prepared.capturedFrameCount == 0) {
                                toaster.show("本轮画面未就绪或采集失败，仅发送语音内容。", type = ToastType.Warning)
                            } else if (prepared.failedOcrCount > 0) {
                                toaster.show(
                                    if (prepared.failedOcrCount == prepared.capturedFrameCount) {
                                        "通话画面 OCR 识别失败，本轮仅发送语音内容。"
                                    } else {
                                        "部分通话画面 OCR 识别失败，已继续发送可识别的画面和语音。"
                                    },
                                    type = ToastType.Warning,
                                )
                            }
                        }
                        prepared.parts
                    }
                }
            }
        val visualSendStatusCallback: ((VoiceCallVisualSendStatus) -> Unit)? =
            if (activeVisualSessionId != null && visualAttemptId != null) {
                { status: VoiceCallVisualSendStatus ->
                    visualRound.submissionStatus(activeVisualSessionId, visualAttemptId, status)
                }
            } else null
        vm.handleMessageSend(
            content = listOf(UIMessagePart.Text(contentText)),
            requestMode = ChatRequestMode.VoiceCall,
            includeVoiceCallConnectedEvent = includeConnectedEvent,
            requestMessagePartsProvider = visualPartsProvider,
            onVoiceCallVisualSendStatus = visualSendStatusCallback,
        )
        hasSubmittedUserMessage = true
        onMessageSubmitted()
        return true
    }

    fun finishKeyboardInput() {
        if (!keyboardCaptureActive) return
        val session = keyboardInputSession
        if (submittedKeyboardInputSession == session) return
        val text = keyboardInput.trim()
        if (text.isBlank()) return
        submittedKeyboardInputSession = session
        val sent = sendCapturedText(text)
        if (sent) {
            keyboardCaptureActive = false
            keyboardInput = ""
            keyboardController?.hide()
        } else {
            submittedKeyboardInputSession = 0
        }
    }

    fun finishCallAsrInput() {
        if (submittingCallAsr) return
        submittingCallAsr = true
        callScope.launch {
            callAsr.stop()
            val completed = withTimeoutOrNull(2_500) {
                callAsr.state.first { !it.isRecording }
            }
            val result = callAsr.state.value
            recordVoiceCallFlow(
                "按键 ASR结束 status=" + result.status +
                    ", chars=" + result.transcript.length + ", completed=" + (completed != null)
            )
            if (result.status != me.rerere.asr.ASRStatus.Error && sendCapturedText(result.transcript)) {
                keyboardInput = ""
                submittedKeyboardInput = result.transcript.trim()
            }
            submittingCallAsr = false
        }
    }

    fun keepPendingKeyboardInputAlive() {
        if (keyboardInput.trim().isBlank()) return
        if (!keyboardCaptureActive) {
            keyboardInputSession++
            submittedKeyboardInputSession = 0
            keyboardCaptureActive = true
            submittedKeyboardInput = ""
        } else if (submittedKeyboardInputSession == keyboardInputSession) {
            keyboardInputSession++
            submittedKeyboardInputSession = 0
        }
    }

    fun updateKeyboardInput(value: String) {
        keyboardInput = value
        if (value.trim().isNotBlank()) {
            keepPendingKeyboardInputAlive()
        }
    }

    fun requestKeyboardVoiceInputShow() {
        keyboardInputFieldKey++
        keyboardShowRequest++
    }

    fun startKeyboardVoiceInput(interruptCurrentReply: Boolean) {
        if (!canStartInput()) return
        if (interruptCurrentReply) {
            interruptCurrentReplyForInput()
        }
        submittedKeyboardInput = ""
        keyboardInput = ""
        keyboardInputSession++
        keyboardCaptureActive = true
        requestKeyboardVoiceInputShow()
    }

    fun showKeyboardVoiceInputAgain() {
        keepPendingKeyboardInputAlive()
        if (keyboardInput.trim().isBlank()) {
            keyboardCaptureActive = true
        }
        requestKeyboardVoiceInputShow()
    }

    LaunchedEffect(keyboardCaptureActive, keyboardShowRequest, keyboardInputFieldKey) {
        if (keyboardCaptureActive) {
            focusManager.clearFocus(force = true)
            delay(50)
            keyboardFocusRequester.requestFocus()
            inputMethodManager?.restartInput(view)
            delay(150)
            keyboardController?.show()
            inputMethodManager?.showSoftInput(view, 0)
            delay(250)
            keyboardFocusRequester.requestFocus()
            inputMethodManager?.restartInput(view)
            keyboardController?.show()
            inputMethodManager?.showSoftInput(view, 0)
            delay(350)
            keyboardFocusRequester.requestFocus()
            keyboardController?.show()
            inputMethodManager?.showSoftInput(view, 0)
        }
    }

    LaunchedEffect(keyboardCaptureActive, keyboardInput) {
        val text = keyboardInput.trim()
        if (keyboardCaptureActive && text.isNotBlank()) {
            delay(900)
            if (keyboardInput.trim() == text) {
                finishKeyboardInput()
            }
        }
    }

    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(keyboardCaptureActive, isImeVisible, pendingUserInputText, keyboardInputSession, submittedKeyboardInputSession) {
        if (keyboardCaptureActive &&
            submittedKeyboardInputSession != keyboardInputSession &&
            !isImeVisible &&
            pendingUserInputText.isNotBlank()
        ) {
            delay(180)
            if (keyboardCaptureActive &&
                submittedKeyboardInputSession != keyboardInputSession &&
                !isImeVisible &&
                pendingUserInputText.isNotBlank()
            ) {
                finishKeyboardInput()
            }
        }
    }

    LaunchedEffect(visibleMessages.size, submittedKeyboardInput) {
        if (submittedKeyboardInput.isNotBlank() &&
            visibleMessages.any { it.role == MessageRole.USER && it.toText().trim() == submittedKeyboardInput }
        ) {
            submittedKeyboardInput = ""
        }
    }

    fun finishVisualQuestion(
        text: String,
        sessionId: String? = visualRound.currentSessionId(),
    ): Boolean {
        if (sessionId == null || !visualRound.isCurrent(sessionId)) return false
        if (visualRoundState.status == VoiceCallVisualRoundStatus.SENDING ||
            visualRoundState.status == VoiceCallVisualRoundStatus.SENT || visualRoundState.completed
        ) return false
        val question = text.ifBlank { VOICE_CALL_VISUAL_DEFAULT_QUESTION }
        visualSpeechStarted = false
        return sendCapturedText(question, sessionId)
    }

    val duplexInput = rememberVoiceCallDuplexInput(
        enabled = !isHistory && !voiceCallResultReported,
        permissionGranted = asrPermission.allRequiredPermissionsGranted,
        providerId = settings.getSelectedASRProvider()?.id,
        onSpeechStarted = {
            if (visualRound.currentSessionId() != null) visualSpeechStarted = true
        },
        onEmptyFinal = {
            val sessionId = visualRound.currentSessionId()
            if (sessionId != null) finishVisualQuestion("", sessionId)
        },
        onInterrupt = {
            if (!voiceCallResultReported) interruptCurrentReplyForInput()
        },
        onSubmit = { text ->
            val sessionId = visualRound.currentSessionId()
            if (sessionId == null || !finishVisualQuestion(text, sessionId)) {
                visualSpeechStarted = false
                sendCapturedText(text)
            }
        },
    )
    stopDuplexInput[0] = duplexInput.stop
    val autoListenConfigured = !isHistory && duplexInput.asrState.isAvailable
    val autoListenEnabled = autoListenConfigured && asrPermission.allRequiredPermissionsGranted
    val duplexListening = autoListenEnabled && !duplexInput.muted &&
        duplexInput.asrState.status == me.rerere.asr.ASRStatus.Listening && duplexInput.error == null
    val visualPreparationTitle = if (visualRoundState.isPreparingGeneration) {
        if (recognizingVisual) "正在识图中" else "正在准备画面"
    } else null
    val aiReplyDisplayActive = aiReplyActive && visualPreparationTitle == null

    fun startVisualQuestion() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (isHistory || !visualFeatureEnabled || !supportsCallImagesNow) return
        visualSpeechStarted = duplexInput.pendingText.isNotBlank()
        val useOcrModel = useSeparateVisualOcrNow
        val primaryModelSupportsImages = callModelSupportsImagesNow
        val sessionId = UUID.randomUUID().toString()
        val request = callScope.async(start = CoroutineStart.LAZY) {
            try {
                visualCapture.prepareRequest(
                    useOcrModel = useOcrModel,
                    mainModelSupportsImages = primaryModelSupportsImages,
                    onFramesCaptured = { frames -> visualRound.framesCaptured(sessionId, frames) },
                    onRecognitionChanged = { recognizing -> visualRound.recognitionChanged(sessionId, recognizing) },
                ).also { prepared -> visualRound.captureFinished(sessionId, prepared) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                visualRound.captureFailed(sessionId)
                throw error
            }
        }
        visualRound.replace(sessionId, request)
        request.start()
        val shouldSubmitImmediately = duplexInput.muted ||
            !asrPermission.allRequiredPermissionsGranted ||
            !autoListenConfigured || duplexInput.error != null
        if (shouldSubmitImmediately) {
            if (finishVisualQuestion(duplexInput.pendingText, sessionId)) {
                duplexInput.pendingText = ""
            }
        }
    }

    val currentStartVisualQuestion by rememberUpdatedState(newValue = { startVisualQuestion() })
    val canCaptureVisualNow by rememberUpdatedState(visualCameraState.enabled || visualScreenState.active)
    LaunchedEffect(visualCapture, callId) {
        VoiceCallFloatingBallActions.events.collect { action ->
            when (action) {
                VoiceCallFloatingBallActions.Action.RETURN_TO_CALL -> Unit
                VoiceCallFloatingBallActions.Action.CAPTURE_AND_SEND -> {
                    if (canCaptureVisualNow) {
                        currentStartVisualQuestion()
                    } else {
                        toaster.show("请先开启视频或屏幕共享，再截图发送。", type = ToastType.Warning)
                    }
                }
            }
        }
    }
    LaunchedEffect(duplexInput.error, visualRoundState.sessionId) {
        val sessionId = visualRound.currentSessionId()
        if (duplexInput.error != null && sessionId != null) {
            if (finishVisualQuestion(duplexInput.pendingText, sessionId)) {
                duplexInput.pendingText = ""
            }
        }
    }
    LaunchedEffect(asrPermission.allRequiredPermissionsGranted, visualRoundState.sessionId) {
        val sessionId = visualRound.currentSessionId()
        if (!asrPermission.allRequiredPermissionsGranted && sessionId != null &&
            !visualRoundState.completed
        ) {
            if (finishVisualQuestion(duplexInput.pendingText, sessionId)) {
                duplexInput.pendingText = ""
            }
        }
    }
    LaunchedEffect(callAsrState.status, callAsrState.isAvailable, callAsrState.errorMessage) {
        if (!isHistory && !autoListenEnabled) {
            recordVoiceCallFlow(
                "按键 ASR状态 provider=" +
                    (settings.getSelectedASRProvider()?.javaClass?.simpleName ?: "none") +
                    ", status=" + callAsrState.status +
                    ", available=" + callAsrState.isAvailable +
                    ", hasError=" + (callAsrState.errorMessage != null)
            )
        }
        callAsrState.errorMessage?.let { toaster.show(it, type = ToastType.Error) }
    }
    LaunchedEffect(autoListenConfigured, asrPermission.allRequiredPermissionsGranted) {
        if (autoListenConfigured && !asrPermission.allRequiredPermissionsGranted) {
            asrPermission.requestPermissions()
        }
    }
    LaunchedEffect(duplexInput.error) {
        duplexInput.error?.let { toaster.show(it, type = ToastType.Warning) }
    }
    LaunchedEffect(duplexInput.fallback, duplexInput.error) {
        if (autoListenEnabled) {
            recordVoiceCallFlow(
                "全双工 ASR状态 fallback=" + duplexInput.fallback +
                    ", hasError=" + (duplexInput.error != null)
            )
        }
    }
    DisposableEffect(duplexInput, callId, isHistory) {
        onDispose {
            duplexInput.stop()
            callAsr.stop()
            if (!isHistory) {
                tts.setRealtimeDialoguePreconnectionEnabled(false)
                tts.stop()
                if (!reportedCallState.value) vm.stopGeneration()
            }
        }
    }

    if (useRealtimeSocketTts) {
        BindVoiceCallRealtimeTtsPlayback(
            state = speechPlayback,
            awaitInitialAssistantReply = awaitInitialAssistantReply,
            speechInput = vm.voiceCallSpeech,
            loadingJob = vm.conversationJob,
            ttsOnlyReadQuoted = settings.displaySetting.ttsOnlyReadQuoted,
            fullDuplex = fullDuplex,
            tts = tts,
            filesManager = filesManager,
            recordFlow = ::recordVoiceCallFlow,
        )
    } else {
        BindVoiceCallSpeechPlayback(
            state = speechPlayback,
            awaitInitialAssistantReply = awaitInitialAssistantReply,
            speechInput = vm.voiceCallSpeech,
            loadingJob = loadingJob,
            useWholeReplyTts = useWholeReplyTts,
            ttsOnlyReadQuoted = settings.displaySetting.ttsOnlyReadQuoted,
            tts = tts,
            filesManager = filesManager,
            recordFlow = ::recordVoiceCallFlow,
        )
    }

    LaunchedEffect(ttsError) {
        if (!isHistory && visible && ttsError?.isNotBlank() == true) {
            toaster.show(VOICE_CALL_UNAVAILABLE_MESSAGE, type = ToastType.Error)
        }
    }

    DisposableEffect(Unit) {
        routeActivity?.suppressVolumeKeyListeners = true
        onDispose {
            routeActivity?.suppressVolumeKeyListeners = false
            keyboardController?.hide()
        }
    }

    val compactForIme = keyboardCaptureActive || isImeVisible
    val contentVerticalPadding = if (compactForIme) 8.dp else 56.dp
    val messageListVerticalPadding = if (compactForIme) 6.dp else 20.dp
    val dialogHorizontalPadding = if (compactForIme) 14.dp else 20.dp
    val dialogTopPadding = if (compactForIme) 10.dp else 20.dp
    val dialogBottomPadding = if (compactForIme) 8.dp else 20.dp
    LaunchedEffect(visualRoundState.sessionId, visualRoundState.completed, aiReplyActive, visualSpeechStarted) {
        val sessionId = visualRoundState.sessionId ?: return@LaunchedEffect
        if (!visualRoundState.completed || aiReplyActive || visualSpeechStarted) return@LaunchedEffect
        visualRound.finishReply(sessionId)
        delay(1_500)
        visualRound.clear(sessionId)
    }
    val callActivityStatus = when {
        voiceCallResultReported -> "通话已结束，正在保存记录…"
        !ttsProviderReady -> "正在连接语音模型..."
        !ttsAvailable -> "电话里的 AI 声音使用「文本转语音」里选中的语音模型。"
        !asrPermission.allRequiredPermissionsGranted -> "电话输入需要麦克风权限。"
        duplexInput.muted -> "聆听已静音，点击按钮恢复聆听。"
        autoListenEnabled && duplexInput.asrState.status == me.rerere.asr.ASRStatus.Connecting -> "正在连接 ASR..."
        duplexListening -> "正在持续聆听，识别完成后自动发送；说话时可打断 AI。"
        callAsrState.isRecording -> "正在使用已配置的 ASR 识别语音。"
        duplexInput.error != null && autoListenEnabled -> "ASR连接失败，静音后再恢复可重试。"
        aiReplyDisplayActive -> "AI 正在回复中"
        callAsrState.isAvailable -> "点按语音输入，使用已配置的 ASR。"
        else -> "未配置 ASR，点按语音输入使用输入法麦克风。"
    }
    val visualActivityStatus = when {
        recognizingVisual -> "正在识图中"
        capturingVisualFrame -> "正在截屏中"
        visualRoundState.status == VoiceCallVisualRoundStatus.SENDING -> "画面正在发送"
        visualRoundState.status == VoiceCallVisualRoundStatus.SENT -> "画面已发送"
        visualRoundState.status == VoiceCallVisualRoundStatus.FAILED -> "发送失败，画面已保留"
        visualRoundState.status == VoiceCallVisualRoundStatus.READY -> "已截图，可以说话"
        visualScreenState.active -> "正在共享屏幕中"
        else -> null
    }
    val floatingCallStatus = when {
        voiceCallResultReported -> null
        aiReplyDisplayActive -> "AI 正在回复中"
        duplexInput.muted -> "聆听已静音"
        autoListenEnabled && duplexInput.asrState.status == me.rerere.asr.ASRStatus.Connecting -> "正在连接 ASR..."
        duplexListening -> "正在持续聆听"
        callAsrState.isRecording -> "正在使用已配置的 ASR 识别"
        else -> null
    }
    val floatingStatus = listOfNotNull(visualActivityStatus, floatingCallStatus)
        .joinToString(" · ")
    LaunchedEffect(floatingStatus, visualScreenState.active, visualRoundState.thumbnail) {
        if (visualScreenState.active) {
            VoiceCallScreenCaptureService.setFloatingBallFeedback(
                status = floatingStatus,
                thumbnail = visualRoundState.thumbnail?.jpeg,
            )
        }
    }
    LaunchedEffect(visualScreenState.active, asrPermission.allRequiredPermissionsGranted) {
        if (visualScreenState.active && asrPermission.allRequiredPermissionsGranted) {
            VoiceCallScreenCaptureService.refreshForegroundForMicrophone()
        }
    }
    val callHazeState = rememberHazeState()

    Dialog(
        onDismissRequest = {
            // Closing the IME can be reported as a dialog dismiss on some ROMs.
            // Only explicit close buttons should end the call.
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        )
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            CompositionLocalProvider(LocalLiquidGlassHaze provides callHazeState) {
                // Capture the base color too: without a call wallpaper, the inner background emits nothing.
                Box(Modifier.fillMaxSize().hazeSource(callHazeState).background(MaterialTheme.colorScheme.background)) {
                    AssistantBackground(
                        setting = settings,
                        modifier = Modifier.fillMaxSize(),
                        assistant = callAssistant,
                        useVoiceCallBackground = true,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(
                            start = dialogHorizontalPadding,
                            top = dialogTopPadding,
                            end = dialogHorizontalPadding,
                            bottom = dialogBottomPadding,
                        )
                ) {
                key(keyboardInputFieldKey) {
                    BasicTextField(
                        value = keyboardInput,
                        onValueChange = ::updateKeyboardInput,
                        modifier = Modifier
                            .size(1.dp)
                            .focusRequester(keyboardFocusRequester)
                            .align(Alignment.BottomCenter),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { finishKeyboardInput() },
                            onDone = { finishKeyboardInput() },
                        ),
                        singleLine = true,
                    )
                }

                TextButton(
                    onClick = { showVoiceCallFlowDialog = true },
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text("排查流程")
                }
                IconButton(
                    onClick = { if (isHistory) onDismiss() else hangUp() },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(HugeIcons.Cancel01, contentDescription = if (isHistory) "关闭" else "挂断")
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .padding(horizontal = 8.dp, vertical = contentVerticalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    if (aiReplyActive) {
                        KeepScreenOn()
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        VoiceCallAvatarPair(
                            userAvatar = userAvatar,
                            userName = userName,
                            assistantAvatar = assistantAvatar,
                            assistantName = assistantName,
                            loading = aiReplyDisplayActive,
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = formatCallElapsed(callElapsedMillis),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        if (!compactForIme) {
                            Spacer(Modifier.height(12.dp))

                            Text(
                                text = if (isHistory) "通话记录" else voiceCallTitle(
                                    hasChatModel = hasChatModel,
                                    micPermissionGranted = asrPermission.allRequiredPermissionsGranted,
                                    ttsAvailable = ttsAvailable,
                                    ttsProviderReady = ttsProviderReady,
                                    keyboardCaptureActive = keyboardCaptureActive,
                                    visualPreparationStatus = visualPreparationTitle,
                                    asrAvailable = callAsrState.isAvailable,
                                    asrRecording = duplexListening || callAsrState.isRecording,
                                    loading = aiReplyDisplayActive,
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                            )

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = callActivityStatus,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 520.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    LazyColumn(
                        state = messageListState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = messageListVerticalPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = visibleMessages,
                            key = { it.id.toString() },
                        ) { message ->
                            val displayItems = message.voiceCallDisplayItems(
                                audioSegmentsOverride = if (isHistory) {
                                    historyAudioSegmentsByMessageId[message.id.toString()].orEmpty()
                                } else {
                                    speechPlayback.audioSegments(message.id.toString())
                                },
                                currentAssistantId = currentAssistantId,
                                textGenerationPending = !isHistory && loadingJob != null,
                                showAudioTags = showVoiceCallTags,
                            )
                            val fullReplyTranslationSource = message
                                .voiceCallDisplayTextOrPlainText()
                                .withoutVoiceCallRealtimeEmotionMarker()
                                .voiceCallDisplaySegments()
                                .joinToString("\n") { it.text }
                                .sanitizeVoiceCallTextForTranslation()
                            displayItems.forEachIndexed { index, item ->
                                val bubbleKey = item.translationKey(index)
                                val isTranslationTarget =
                                    message.role == MessageRole.ASSISTANT && bubbleKey != null
                                val translatingText = stringResource(R.string.translating)
                                val translation = if (isTranslationTarget) {
                                    message.voiceCallTranslationForDisplay(
                                        index = index,
                                        itemCount = displayItems.count { it.translationText != null },
                                        loadingText = translatingText,
                                    )
                                } else {
                                    null
                                }
                                VoiceCallMessageBubble(
                                    role = message.role,
                                    item = item,
                                    translation = translation,
                                    onTranslate = if (isTranslationTarget) {
                                        { language ->
                                            vm.translateVoiceCallBubble(
                                                message = message,
                                                bubbleKey = VOICE_CALL_REPLY_TRANSLATION_KEY,
                                                sourceText = fullReplyTranslationSource,
                                                targetLanguage = language,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClearTranslation = if (isTranslationTarget) {
                                        {
                                            vm.clearVoiceCallTranslation(
                                                messageId = message.id,
                                                bubbleKey = VOICE_CALL_REPLY_TRANSLATION_KEY,
                                                clearLegacyTranslation = displayItems.size == 1,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                                if (index < displayItems.lastIndex) {
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                        item(
                            key = "voice-call-pending-user-input",
                        ) {
                            val capturedText = duplexInput.pendingText.ifBlank { pendingBubbleText }
                            if (capturedText.isNotBlank()) {
                                VoiceCallMessageBubble(
                                    role = MessageRole.USER,
                                    item = VoiceCallDisplayItem.Text(capturedText),
                                )
                            }
                        }
                    }

                    if (!isHistory) {
                        VoiceCallVisualControls(
                            capture = visualCapture,
                            enabled = !voiceCallResultReported && callAssistant.voiceCallVisualEnabled,
                            supportsImages = supportsCallImages,
                            visualRoundState = visualRoundState,
                            listening = duplexListening,
                            muted = duplexInput.muted,
                            onCaptureAndSend = ::startVisualQuestion,
                            onError = { toaster.show(it, type = ToastType.Warning) },
                        )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VoiceCallActionBubble(
                            onClick = ::hangUp,
                        ) {
                            Text("挂断")
                        }
                        // TextButton(onClick = { showVoiceCallFlowDialog = true }) {
                        //     Text("排查流程")
                        // }
                        if (autoListenEnabled && asrPermission.allRequiredPermissionsGranted) {
                            AsrButton(
                                state = duplexInput.asrState,
                                muted = duplexInput.muted,
                                enabled = !voiceCallResultReported,
                                onClick = {
                                    val sessionId = visualRound.currentSessionId()
                                    if (!duplexInput.muted && sessionId != null &&
                                        visualRoundState.status != VoiceCallVisualRoundStatus.SENDING &&
                                        (visualRoundState.status != VoiceCallVisualRoundStatus.SENT || visualSpeechStarted)
                                    ) {
                                        finishVisualQuestion(duplexInput.pendingText, sessionId)
                                    }
                                    duplexInput.toggleMute()
                                },
                            )
                        } else VoiceCallActionBubble(
                            enabled = !voiceCallResultReported && !submittingCallAsr && (
                                !asrPermission.allRequiredPermissionsGranted ||
                                !keyboardCaptureActive ||
                                (hasChatModel && ttsAvailable)),

                            onClick = {
                                if (!asrPermission.allRequiredPermissionsGranted) {
                                    asrPermission.requestPermissions()
                                } else if (duplexListening) {
                                    interruptCurrentReplyForInput()
                                } else if (callAsrState.isRecording) {
                                    finishCallAsrInput()
                                } else if (callAsrState.isAvailable) {
                                    if (canStartInput()) {
                                        if (aiReplyActive) interruptCurrentReplyForInput()
                                        keyboardInput = ""
                                        submittedKeyboardInput = ""
                                        recordVoiceCallFlow(
                                            "按键 ASR启动 provider=" +
                                                (settings.getSelectedASRProvider()?.javaClass?.simpleName ?: "none")
                                        )
                                        callAsr.start {}
                                    }
                                } else if (keyboardCaptureActive) {
                                    if (pendingUserInputText.isNotBlank()) {
                                        finishKeyboardInput()
                                    } else {
                                        showKeyboardVoiceInputAgain()
                                    }
                                } else if (pendingUserInputText.isNotBlank()) {
                                    keepPendingKeyboardInputAlive()
                                    finishKeyboardInput()
                                } else {
                                    startKeyboardVoiceInput(
                                        interruptCurrentReply = aiReplyActive
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Voice,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    !asrPermission.allRequiredPermissionsGranted -> "获取麦克风权限"
                                    duplexListening -> "打断 AI"
                                    callAsrState.isRecording -> "结束并发送"
                                    keyboardCaptureActive -> "语音输入"
                                    callAsrState.isAvailable -> "ASR 语音输入"
                                    aiReplyActive -> "打断并说话"
                                    else -> "语音输入"
                                }
                            )
                        }
                     }
                   }
                }

                BackHandler(enabled = cameraPreviewExpanded) {
                    cameraPreviewExpanded = false
                }
                if (visualCameraState.enabled && !visualScreenState.active) {
                    Box(
                        modifier = Modifier
                            .then(
                                if (cameraPreviewExpanded) Modifier.fillMaxSize()
                                else Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 48.dp, end = 8.dp)
                                    .size(width = 92.dp, height = 128.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            .background(if (cameraPreviewExpanded) Color.Black else Color.Transparent)
                            .clickable(enabled = !cameraPreviewExpanded) { cameraPreviewExpanded = true },
                    ) {
                        VoiceCallCameraPreview(visualCapture, Modifier.fillMaxSize())
                        if (cameraPreviewExpanded) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { cameraPreviewExpanded = false }) {
                                    Text("返回")
                                }
                                Row {
                                    TextButton(onClick = visualCapture::switchCamera) {
                                        Text("翻转镜头")
                                    }
                                    TextButton(onClick = {
                                        visualCapture.setCameraEnabled(false)
                                        cameraPreviewExpanded = false
                                    }) {
                                        Text("关闭视频")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    VoiceCallDiagnosticsDialog(
        visible = showVoiceCallFlowDialog,
        steps = voiceCallFlowSteps,
        onCopy = {
            clipboardManager?.setPrimaryClip(
                ClipData.newPlainText(
                    "语音通话排查流程",
                    voiceCallFlowSteps.joinToString("\n"),
                )
            )
            toaster.show("已复制排查流程")
        },
        onClear = { voiceCallFlowSteps.clear() },
        onDismiss = { showVoiceCallFlowDialog = false },
    )
}

/*
@Composable
private fun VoiceCallTroubleshootingDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("语音输入问题/流程") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = """
当前定位的问题：
1. 第一轮语音输入可以发送。
2. 多轮对话到第二轮时，用户已经通过输入法语音说出内容，但 AI 没收到。
3. 用户必须再点一次「语音输入」按钮；这时旧内容可能被换行、清空或重新进入输入法状态。
4. 如果先退出键盘，再点「语音输入」，键盘可能不重新弹出。

修复后的采集流程：
1. 点「语音输入」后，隐藏输入框获得焦点并拉起系统输入法。
2. 用户用输入法麦克风说话，识别文本先进入隐藏输入框，同时在对话里显示为待发送气泡。
3. 文本稳定约 0.9 秒、输入法关闭、或输入法触发发送/完成动作时，立即把这段文本提交给 AI。
4. AI 回复和朗读期间，再点「语音输入」会先停止当前回复，再进入下一轮输入。
5. 第二轮如果输入法已经把文字写进隐藏输入框，即使采集状态刚好被关闭，也会重新激活这一轮采集，不再等用户额外点按钮。
6. 如果按钮被点击时已有未发送文字，会优先发送这段文字，不再先清空。
7. 如果键盘被手动退出，再点「语音输入」会重建隐藏输入框的输入连接，并多次请求系统输入法显示。

测试时请完整按这个顺序验证：
1. 打开语音通话。
2. 点「语音输入」，用输入法麦克风说第一句，等待 AI 收到并回复。
3. AI 回复结束后，说第二句，观察是否自动出现待发送气泡并提交。
4. 手动收起键盘，再点「语音输入」，观察键盘是否重新弹出。
5. 如果第二句已经显示在气泡里，再点「语音输入」，应直接发送该文字，而不是清空。
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        },
    )
}
*/

@Composable
private fun VoiceCallAvatarPair(
    userAvatar: Avatar,
    userName: String,
    assistantAvatar: Avatar,
    assistantName: String,
    loading: Boolean,
) {
    Box(
        modifier = Modifier
            .width(82.dp)
            .height(42.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .align(Alignment.CenterStart),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Box(Modifier.padding(5.dp)) {
                UIAvatar(
                    name = userName,
                    value = userAvatar,
                )
            }
        }
        Surface(
            modifier = Modifier
                .size(42.dp)
                .align(Alignment.CenterEnd)
                .offset(x = (-4).dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Box(Modifier.padding(5.dp)) {
                UIAvatar(
                    name = assistantName,
                    value = assistantAvatar,
                    loading = loading,
                )
            }
        }
    }
}

@Composable
private fun VoiceCallMessageBubble(
    role: MessageRole,
    item: VoiceCallDisplayItem,
    translation: String? = null,
    onTranslate: ((Locale) -> Unit)? = null,
    onClearTranslation: (() -> Unit)? = null,
) {
    val tts = LocalTTSState.current
    val isUser = role == MessageRole.USER
    val liquidGlass = LocalLiquidGlass.current.enabled
    val selectedTtsProvider = LocalSettings.current.getSelectedTTSProvider()
    val showVoiceCallAudioTagAnnotations =
        LocalSettings.current.voiceCallAudioTagMode
            .forVoiceCallProvider(selectedTtsProvider) != VoiceCallAudioTagMode.DISABLED &&
            selectedTtsProvider?.voiceCallAudioTagFormatOrNull() != null
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Spacer(Modifier.width(2.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.78f else 0.86f)
                .widthIn(max = 520.dp),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                val bubbleShape = ChatMessageBubbleShape
                MessageBubbleSurface(
                    modifier = Modifier.widthIn(max = 520.dp),
                    shape = bubbleShape,
                    containerColor = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    applyLiquidGlass = liquidGlass,
                ) {
                    Column {
                    when (item) {
                        VoiceCallDisplayItem.Loading -> {
                            Box(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                RabbitLoadingIndicator(Modifier.size(24.dp))
                            }
                        }

                        is VoiceCallDisplayItem.Voice -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Voice,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = if (item.isWholeReplyAudio) "整段回放" else "语音消息",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${item.durationSeconds}s",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    item.audioSegment?.let { audio ->
                                        IconButton(
                                            onClick = {
                                                tts.playCachedAudio(
                                                    audio.audioUri,
                                                    audio.format,
                                                    audio.sampleRate,
                                                    audio.startPositionMs,
                                                    audio.endPositionMs,
                                                )
                                            },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(HugeIcons.VolumeHigh, contentDescription = if (item.isWholeReplyAudio) "播放整段通话回复" else "播放通话 TTS", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = buildAnnotatedString {
                                        if (showVoiceCallAudioTagAnnotations) {
                                            appendVoiceCallAudioTagAwareText(item.text, colorScheme)
                                        } else {
                                            append(item.text)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        is VoiceCallDisplayItem.Text -> {
                            Text(
                                text = item.text,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    if (onTranslate != null && onClearTranslation != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, top = 2.dp, end = 14.dp, bottom = 4.dp),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    ) {
                        TranslateMessageButton(
                            onTranslate = onTranslate,
                            onClearTranslation = onClearTranslation,
                            showLabel = true,
                            defaultLanguage = Locale.SIMPLIFIED_CHINESE,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    }

                    translation?.takeIf { it.isNotBlank() }?.let { content ->
                    CollapsibleTranslationText(
                        content = content,
                        onClickCitation = {},
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                        showHeader = false,
                        showCollapseControl = false,
                    )
                    }
                    }
                }
            }
        }
    }
}

private fun formatCallElapsed(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun voiceCallTitle(
    hasChatModel: Boolean,
    micPermissionGranted: Boolean,
    ttsAvailable: Boolean,
    ttsProviderReady: Boolean,
    keyboardCaptureActive: Boolean,
    visualPreparationStatus: String?,
    asrAvailable: Boolean,
    asrRecording: Boolean,
    loading: Boolean,
): String = when {
    !hasChatModel -> "请先选择聊天模型"
    !ttsProviderReady -> "正在连接语音模型"
    !ttsAvailable -> "请先到「设置 > 语音服务 > 文本转语音」选择语音模型"
    !micPermissionGranted -> "请允许麦克风权限"
    keyboardCaptureActive -> "等待输入法语音"
    visualPreparationStatus != null -> visualPreparationStatus
    loading -> "AI 正在回复中"
    asrRecording -> "正在使用 ASR 识别"
    asrAvailable -> "ASR 语音模式"
    else -> "输入法语音模式"
}
