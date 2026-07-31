package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import androidx.core.net.toUri
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.VoiceCallAudioSegment
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.voice.VoiceCallCompletion
import me.rerere.rikkahub.data.voice.voiceCallRecord
import me.rerere.rikkahub.data.voice.VOICE_CALL_UNAVAILABLE_MESSAGE
import me.rerere.rikkahub.data.voice.voiceCallAudioTagFormatOrNull
import me.rerere.rikkahub.data.voice.voiceCallDisplayTextOrPlainText
import me.rerere.rikkahub.data.voice.voiceCallSpeechTextOrPlainText
import me.rerere.rikkahub.service.ChatRequestMode
import me.rerere.rikkahub.service.sanitizeVoiceCallTextForSpeech
import me.rerere.rikkahub.ui.components.richtext.appendVoiceCallAudioTagAwareText
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.tts.model.PlaybackStatus
import org.koin.compose.koinInject

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
    onVoiceCallClosed: (failureMessage: String?, completion: VoiceCallCompletion?) -> Unit = { _, _ -> },
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
    val ttsError by tts.error.collectAsState()
    val playbackState by tts.playbackState.collectAsState()
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)

    var voiceReplyPending by remember { mutableStateOf(awaitInitialAssistantReply) }
    var spokenMessageId by remember { mutableStateOf<String?>(null) }
    var queuedTextLength by remember { mutableStateOf(0) }
    var visibleTextLength by remember { mutableStateOf(0) }
    val queuedSpeechSegments = remember { mutableStateListOf<VoiceCallSpeechSegment>() }
    val interruptedAssistantVisibleTextLengths = remember { mutableStateMapOf<String, Int>() }
    var keyboardCaptureActive by remember { mutableStateOf(false) }
    var keyboardShowRequest by remember { mutableStateOf(0) }
    var keyboardInputFieldKey by remember { mutableStateOf(0) }
    var keyboardInputSession by remember { mutableStateOf(0) }
    var submittedKeyboardInputSession by remember { mutableStateOf(0) }
    var keyboardInput by remember { mutableStateOf("") }
    var submittedKeyboardInput by remember { mutableStateOf("") }
    var voiceCallResultReported by remember(initialVoiceCallToolCallId) { mutableStateOf(false) }
    // Keep this dormant troubleshooting dialog code nearby. It was useful for
    // diagnosing IME voice-input lifecycle issues and may be re-enabled later.
    // var showVoiceCallFlowDialog by remember { mutableStateOf(true) }
    val callStartedAt = remember { System.currentTimeMillis() }
    val callId = remember { UUID.randomUUID().toString() }
    val callStartMessageIds = remember { conversation.currentMessages.map { it.id.toString() }.toSet() }
    val audioSegmentsByMessageId = remember { mutableStateMapOf<String, List<VoiceCallAudioSegment>>() }
    var callElapsedMillis by remember { mutableStateOf(0L) }
    val callStartMessageCount = remember { conversation.currentMessages.size }
    var voiceRequestStartMessageCount by remember { mutableStateOf(conversation.currentMessages.size) }

    val initialAssistantMessage = initialAssistantMessageId?.let { messageId ->
        conversation.currentMessages.firstOrNull {
            it.role == MessageRole.ASSISTANT && it.id.toString() == messageId
        }
    }
    val visibleMessages = if (isHistory) {
        conversation.currentMessages.filter { message ->
            message.voiceCallRecord()?.callId == historyCallId &&
                (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
        }
    } else {
        conversation.currentMessages.filterIndexed { index, message ->
            (index >= callStartMessageCount || message.id.toString() == initialAssistantMessageId) &&
                (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
        }
    }
    val historyDurationMillis = visibleMessages.firstNotNullOfOrNull { it.voiceCallRecord()?.durationSeconds }?.times(1000L) ?: 0L
    val messageListState = rememberLazyListState()
    // The incoming-call tool message predates the call's first generated reply.
    // Prefer each reply created after the current voice input; keep the tool
    // message only as a fallback while the first reply is still starting.
    val latestGeneratedAssistantMessage = conversation.currentMessages
        .drop(voiceRequestStartMessageCount)
        .lastOrNull { it.role == MessageRole.ASSISTANT }
    val currentAssistantMessage = latestGeneratedAssistantMessage
        ?: initialAssistantMessage?.takeIf {
            loadingJob != null || it.toText().isNotBlank()
        }
    val currentAssistantId = currentAssistantMessage?.id?.toString()
    val currentAssistantText = currentAssistantMessage?.voiceCallDisplayTextOrPlainText().orEmpty()
    val currentAssistantSpeechText = currentAssistantMessage?.voiceCallSpeechTextOrPlainText().orEmpty()
    val pendingUserInputText = keyboardInput.trim()
    val pendingBubbleText = pendingUserInputText.ifBlank { submittedKeyboardInput }
    val latestCurrentAssistantText by rememberUpdatedState(currentAssistantText)
    val latestLoadingJob by rememberUpdatedState(loadingJob)
    val ttsPlaybackActive = playbackState.status == PlaybackStatus.Playing ||
        playbackState.status == PlaybackStatus.Buffering ||
        playbackState.status == PlaybackStatus.Paused

    fun isReplyActuallyActive(): Boolean {
        return loadingJob != null ||
            ttsPlaybackActive ||
            (voiceReplyPending && currentAssistantId != null && visibleTextLength < currentAssistantText.length)
    }

    fun resetSpeechProgress(messageId: String?) {
        spokenMessageId = messageId
        queuedTextLength = 0
        visibleTextLength = 0
        queuedSpeechSegments.clear()
    }

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
        if (isHistory) return@LaunchedEffect
        while (true) {
            callElapsedMillis = System.currentTimeMillis() - callStartedAt
            delay(1000)
        }
    }

    fun hangUp(failureMessage: String? = null) {
        keyboardCaptureActive = false
        keyboardInput = ""
        submittedKeyboardInput = ""
        submittedKeyboardInputSession = keyboardInputSession
        keyboardController?.hide()
        tts.stop()
        voiceReplyPending = false
        if (!voiceCallResultReported) {
            voiceCallResultReported = true
            val callMessages = conversation.currentMessages.filter { message ->
                message.id.toString() !in callStartMessageIds &&
                    (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
            }
            val aiMessages = callMessages.filter { message ->
                message.role == MessageRole.ASSISTANT && message.voiceCallDisplayTextOrPlainText().isNotBlank()
            }
            val completion = VoiceCallCompletion(
                callId = callId,
                durationSeconds = ((callElapsedMillis + 999L) / 1000L).toInt().coerceAtLeast(1),
                messageIds = callMessages.map { it.id.toString() }.toSet(),
                cardAnchorMessageId = aiMessages.firstOrNull()?.id?.toString(),
                audioSegmentsByMessageId = audioSegmentsByMessageId.toMap(),
            )
            onVoiceCallClosed(failureMessage, completion)
        }
        onDismiss()
    }

    LaunchedEffect(visible, ttsAvailable) {
        if (!isHistory && visible && !ttsAvailable) {
            toaster.show(VOICE_CALL_UNAVAILABLE_MESSAGE, type = ToastType.Error)
            hangUp(VOICE_CALL_UNAVAILABLE_MESSAGE)
        }
    }

    fun canStartInput(): Boolean {
        if (!hasChatModel) {
            toaster.show("请先选择聊天模型", type = ToastType.Error)
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

    fun interruptCurrentReplyForInput() {
        currentAssistantId?.let { messageId ->
            interruptedAssistantVisibleTextLengths[messageId] = visibleTextLength
        }
        if (loadingJob != null) {
            vm.stopGeneration()
        }
        tts.stop()
        voiceReplyPending = false
        resetSpeechProgress(null)
    }

    fun sendCapturedText(text: String): Boolean {
        val contentText = text.trim()
        if (contentText.isBlank()) {
            toaster.show("没有识别到语音", type = ToastType.Warning)
            return false
        }
        if (isReplyActuallyActive()) {
            tts.stop()
        }
        resetSpeechProgress(null)
        voiceRequestStartMessageCount = conversation.currentMessages.size
        voiceReplyPending = true
        submittedKeyboardInput = contentText
        vm.handleMessageSend(
            content = listOf(UIMessagePart.Text(contentText)),
            requestMode = ChatRequestMode.VoiceCall,
        )
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

    LaunchedEffect(voiceReplyPending, currentAssistantId) {
        if (voiceReplyPending && currentAssistantId != null && spokenMessageId != currentAssistantId) {
            resetSpeechProgress(currentAssistantId)
        }
    }

    LaunchedEffect(awaitInitialAssistantReply) {
        if (awaitInitialAssistantReply) {
            voiceReplyPending = true
            resetSpeechProgress(null)
        }
    }

    LaunchedEffect(
        voiceReplyPending,
        currentAssistantId,
        currentAssistantText,
        currentAssistantSpeechText,
        loadingJob,
        queuedTextLength,
    ) {
        if (!voiceReplyPending || currentAssistantId == null) return@LaunchedEffect
        if (spokenMessageId != currentAssistantId) {
            resetSpeechProgress(currentAssistantId)
        }
        if (loadingJob != null) return@LaunchedEffect

        val speakable = currentAssistantSpeechText
            .sanitizeVoiceCallTextForSpeech()
            .trim()
        if (speakable.isBlank()) return@LaunchedEffect
        if (queuedTextLength == currentAssistantText.length && queuedSpeechSegments.isNotEmpty()) {
            return@LaunchedEffect
        }

        queuedTextLength = currentAssistantText.length
        queuedSpeechSegments.clear()
        queuedSpeechSegments += VoiceCallSpeechSegment(
            text = speakable,
            endLength = currentAssistantText.length,
        )
    }

    LaunchedEffect(spokenMessageId) {
        val messageId = spokenMessageId ?: return@LaunchedEffect
        var nextSpeechIndex = 0
        while (spokenMessageId == messageId) {
            val segment = queuedSpeechSegments.getOrNull(nextSpeechIndex)
            if (segment == null) {
                if (latestLoadingJob == null &&
                    latestCurrentAssistantText.isNotBlank() &&
                    queuedTextLength >= latestCurrentAssistantText.length &&
                    visibleTextLength >= latestCurrentAssistantText.length
                ) {
                    voiceReplyPending = false
                    break
                }
                delay(60)
                continue
            }

            tts.speak(
                text = segment.text.sanitizeVoiceCallTextForSpeech(),
                flushCalled = true,
                chunked = false,
                onAudioReady = { response ->
                    val file = filesManager.saveManagedFromBytes(
                        folder = FileFolders.UPLOAD,
                        bytes = response.audioData,
                        displayName = "voice-call-audio",
                        mimeType = "audio/*",
                    )
                    val audioSegment = VoiceCallAudioSegment(
                        text = segment.text,
                        audioUri = filesManager.getFile(file).toUri().toString(),
                        format = response.format.name,
                        sampleRate = response.sampleRate,
                    )
                    audioSegmentsByMessageId[messageId] =
                        audioSegmentsByMessageId[messageId].orEmpty() + audioSegment
                }
            )

            val startState = withTimeoutOrNull(20_000) {
                tts.playbackState
                    .filter {
                        it.status == PlaybackStatus.Playing ||
                            it.status == PlaybackStatus.Ended ||
                            it.status == PlaybackStatus.Idle ||
                            it.status == PlaybackStatus.Error
                    }
                    .first()
            }

            when (startState?.status) {
                PlaybackStatus.Playing -> {
                    if (spokenMessageId != messageId) return@LaunchedEffect
                    latestCurrentAssistantText.voiceCallDisplaySegments().forEach { displaySegment ->
                        if (spokenMessageId != messageId) return@LaunchedEffect
                        visibleTextLength = maxOf(visibleTextLength, displaySegment.endLength)
                        delay(voiceCallRevealDelayMillis(displaySegment.text))
                    }
                }

                PlaybackStatus.Ended,
                PlaybackStatus.Idle,
                PlaybackStatus.Error,
                null -> {
                    visibleTextLength = maxOf(visibleTextLength, segment.endLength)
                }

                else -> Unit
            }

            if (startState?.status == PlaybackStatus.Playing) {
                withTimeoutOrNull(180_000) {
                    tts.playbackState
                        .filter {
                            it.status == PlaybackStatus.Ended ||
                                it.status == PlaybackStatus.Error ||
                                it.status == PlaybackStatus.Idle
                        }
                        .first()
                }
                if (spokenMessageId != messageId) return@LaunchedEffect
                visibleTextLength = maxOf(visibleTextLength, segment.endLength)
            }

            nextSpeechIndex++
        }
    }

    LaunchedEffect(ttsError) {
        if (!isHistory && visible && ttsError?.isNotBlank() == true) {
            toaster.show(VOICE_CALL_UNAVAILABLE_MESSAGE, type = ToastType.Error)
            hangUp(VOICE_CALL_UNAVAILABLE_MESSAGE)
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
    val aiReplyActive = isReplyActuallyActive()

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
            modifier = Modifier.fillMaxSize()
        ) {
            AssistantBackground(
                setting = settings,
                modifier = Modifier.fillMaxSize(),
                assistant = callAssistant,
                useVoiceCallBackground = true,
            )
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
                            loading = aiReplyActive,
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
                                    keyboardCaptureActive = keyboardCaptureActive,
                                    loading = aiReplyActive,
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                            )

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = when {
                                    !ttsAvailable -> "电话里的 AI 声音使用「文本转语音」里选中的语音模型。"
                                    !asrPermission.allRequiredPermissionsGranted -> "电话输入需要麦克风权限。"
                                    aiReplyActive -> "AI 正在回复..."
                                    else -> "点按语音输入，用输入法麦克风说话。"
                                },
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
                                audioSegmentsOverride = audioSegmentsByMessageId[message.id.toString()].orEmpty(),
                                currentAssistantId = currentAssistantId,
                                voiceReplyPending = voiceReplyPending,
                                visibleTextLength = visibleTextLength,
                                visibleTextOverride = interruptedAssistantVisibleTextLengths[message.id.toString()],
                            )
                            displayItems.forEachIndexed { index, item ->
                                VoiceCallMessageBubble(
                                    role = message.role,
                                    item = item,
                                )
                                if (index < displayItems.lastIndex) {
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                        item(
                            key = "voice-call-pending-user-input",
                        ) {
                            if (pendingBubbleText.isNotBlank()) {
                                VoiceCallMessageBubble(
                                    role = MessageRole.USER,
                                    item = VoiceCallDisplayItem.Text(pendingBubbleText),
                                )
                            }
                        }
                    }

                    if (!isHistory) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(onClick = ::hangUp) {
                            Text("挂断")
                        }
                        // TextButton(onClick = { showVoiceCallFlowDialog = true }) {
                        //     Text("排查流程")
                        // }
                        Button(
                            enabled = !asrPermission.allRequiredPermissionsGranted ||
                                !keyboardCaptureActive ||
                                (hasChatModel && ttsAvailable),
                            onClick = {
                                if (!asrPermission.allRequiredPermissionsGranted) {
                                    asrPermission.requestPermissions()
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
                                    keyboardCaptureActive -> "语音输入"
                                    aiReplyActive -> "打断并说话"
                                    else -> "语音输入"
                                }
                            )
                        }
                     }
                   }
                }
            }
        }
    }

    // VoiceCallTroubleshootingDialog(
    //     show = showVoiceCallFlowDialog,
    //     onDismiss = { showVoiceCallFlowDialog = false },
    // )
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
) {
    val tts = LocalTTSState.current
    val isUser = role == MessageRole.USER
    val showVoiceCallAudioTagAnnotations =
        LocalSettings.current.getSelectedTTSProvider()?.voiceCallAudioTagFormatOrNull() != null
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
            Surface(
                modifier = Modifier.widthIn(max = 520.dp),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 6.dp,
                    bottomEnd = if (isUser) 6.dp else 18.dp,
                ),
                color = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
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
                                    text = "语音消息",
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
                                    IconButton(onClick = { tts.playCachedAudio(audio.audioUri, audio.format, audio.sampleRate) }, modifier = Modifier.size(28.dp)) {
                                        Icon(HugeIcons.VolumeHigh, contentDescription = "播放通话 TTS", modifier = Modifier.size(16.dp))
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
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

private sealed interface VoiceCallDisplayItem {
    data class Text(val text: String) : VoiceCallDisplayItem
    data class Voice(
        val text: String,
        val durationSeconds: Int,
        val audioSegment: VoiceCallAudioSegment? = null,
    ) : VoiceCallDisplayItem
    data object Loading : VoiceCallDisplayItem
}

private data class VoiceCallSpeechSegment(
    val text: String,
    val endLength: Int,
)

private fun me.rerere.ai.ui.UIMessage.voiceCallDisplayItems(
    audioSegmentsOverride: List<VoiceCallAudioSegment> = emptyList(),
    currentAssistantId: String?,
    voiceReplyPending: Boolean,
    visibleTextLength: Int,
    visibleTextOverride: Int?,
): List<VoiceCallDisplayItem> {
    val audioSegments = audioSegmentsOverride.ifEmpty {
        annotations.filterIsInstance<me.rerere.ai.ui.UIMessageAnnotation.VoiceCallRecord>()
            .flatMap { it.audioSegments }
    }
    val fullText = when (role) {
        MessageRole.ASSISTANT -> voiceCallDisplayTextOrPlainText()
        else -> toText()
    }
    val isCurrentAssistant = role == MessageRole.ASSISTANT && id.toString() == currentAssistantId
    if (isCurrentAssistant && (voiceReplyPending || visibleTextOverride != null)) {
        val visibleLength = if (voiceReplyPending) {
            visibleTextLength
        } else {
            visibleTextOverride ?: fullText.length
        }
        val visibleText = fullText.take(visibleLength)
        val items = visibleText.splitVoiceCallDisplayItems(asVoice = true, audioSegments = audioSegments).toMutableList()
        if (voiceReplyPending) {
            if (visibleTextLength <= 0) {
                items += VoiceCallDisplayItem.Loading
            } else if (visibleTextLength < fullText.length) {
                items += VoiceCallDisplayItem.Loading
            }
        }
        return items
    }

    if (role != MessageRole.ASSISTANT || !isCurrentAssistant) {
        return when (role) {
            MessageRole.ASSISTANT -> fullText.splitVoiceCallDisplayItems(asVoice = true, audioSegments = audioSegments)
            else -> listOfNotNull(fullText.takeIf { it.isNotBlank() }?.let(VoiceCallDisplayItem::Text))
        }
    }
    return fullText.splitVoiceCallDisplayItems(asVoice = true, audioSegments = audioSegments)
}

private fun String.splitVoiceCallDisplayItems(
    asVoice: Boolean,
    audioSegments: List<VoiceCallAudioSegment> = emptyList(),
): List<VoiceCallDisplayItem> {
    return voiceCallDisplaySegments()
        .map { segment -> segment.text.toVoiceCallDisplayItem(asVoice, audioSegments.firstOrNull { it.text == segment.text }) }
}

private fun String.voiceCallDisplaySegments(): List<VoiceCallSpeechSegment> {
    val result = mutableListOf<VoiceCallSpeechSegment>()
    var start = 0
    var index = 0
    while (index < length) {
        if (this[index].isVoiceCallSentenceBoundary()) {
            var endExclusive = index + 1
            while (endExclusive < length && this[endExclusive].isVoiceCallSentenceBoundary()) {
                endExclusive++
            }
            val segment = substring(start, endExclusive).trim()
            if (segment.isNotBlank()) {
                result += VoiceCallSpeechSegment(
                    text = segment,
                    endLength = endExclusive,
                )
            }
            start = endExclusive
            while (start < length && this[start].isWhitespace()) {
                start++
            }
            index = start
        } else {
            index++
        }
    }
    val tail = if (start < length) substring(start).trim() else ""
    if (tail.isNotBlank()) {
        result += VoiceCallSpeechSegment(
            text = tail,
            endLength = length,
        )
    }
    return result
}

private fun Char.isVoiceCallSentenceBoundary(): Boolean {
    return this in setOf('。', '！', '？', '.', '!', '?', '…', '\n')
}

private fun String.toVoiceCallDisplayItem(asVoice: Boolean, audioSegment: VoiceCallAudioSegment? = null): VoiceCallDisplayItem {
    return if (asVoice) {
        VoiceCallDisplayItem.Voice(
            text = this,
            durationSeconds = estimateVoiceCallDurationSeconds(this),
            audioSegment = audioSegment,
        )
    } else {
        VoiceCallDisplayItem.Text(this)
    }
}

private fun estimateVoiceCallDurationSeconds(text: String): Int {
    val chineseChars = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    val otherChars = (text.length - chineseChars).coerceAtLeast(0)
    return ((chineseChars / 4.2f) + (otherChars / 9.0f))
        .toInt()
        .coerceIn(1, 60)
}

private fun voiceCallRevealDelayMillis(text: String): Long {
    return (estimateVoiceCallDurationSeconds(text) * 300L)
        .coerceIn(450L, 1_200L)
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
    keyboardCaptureActive: Boolean,
    loading: Boolean,
): String = when {
    !hasChatModel -> "请先选择聊天模型"
    !ttsAvailable -> "请先到「设置 > 语音服务 > 文本转语音」选择语音模型"
    !micPermissionGranted -> "请允许麦克风权限"
    keyboardCaptureActive -> "等待输入法语音"
    loading -> "AI 正在回复"
    else -> "输入法语音模式"
}

private fun consumeFirstVoiceCallSentence(text: String): Pair<String, Int>? {
    if (text.isBlank()) return null
    val boundary = text.indexOfFirst { it in setOf('。', '！', '？', '.', '!', '?', '\n') }
    if (boundary < 0) {
        if (text.length < VOICE_CALL_SOFT_SEGMENT_MIN_LENGTH) return null

        val softBoundary = text
            .take(VOICE_CALL_SOFT_SEGMENT_MAX_LENGTH)
            .indexOfLast { it in setOf('，', '、', '；', ';', ',', ' ') }
            .takeIf { it >= VOICE_CALL_SOFT_SEGMENT_MIN_LENGTH }
            ?: VOICE_CALL_SOFT_SEGMENT_MIN_LENGTH

        var consumed = softBoundary + 1
        while (consumed < text.length && text[consumed].isWhitespace()) {
            consumed++
        }
        return text.take(consumed).trim() to consumed
    }

    var consumed = boundary + 1
    while (consumed < text.length && text[consumed].isWhitespace()) {
        consumed++
    }
    return text.take(consumed).trim() to consumed
}

private const val VOICE_CALL_SOFT_SEGMENT_MIN_LENGTH = 24
private const val VOICE_CALL_SOFT_SEGMENT_MAX_LENGTH = 42
