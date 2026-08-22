package me.rerere.rikkahub.ui.pages.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.momentPersonaName
import me.rerere.rikkahub.data.model.momentScopeId
import me.rerere.rikkahub.data.model.personaScopeId
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.data.voice.voiceCallRecord
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val compressionDiagnostics by vm.compressionDiagnostics.collectAsStateWithLifecycle()
    var showCompressionDiagnostics by rememberSaveable(conversation.id) {
        mutableStateOf(false)
    }

    var previewMode by rememberSaveable { mutableStateOf(false) }
    var showCompressedMessages by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var summaryEditorVisible by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var voiceCallVisible by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var voiceCallHistoryId by rememberSaveable(conversation.id) { mutableStateOf<String?>(null) }
    var awaitInitialVoiceCallReply by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var initialVoiceCallAssistantMessageId by rememberSaveable(conversation.id) { mutableStateOf<String?>(null) }
    var initialVoiceCallToolCallId by rememberSaveable(conversation.id) { mutableStateOf<String?>(null) }
    var handledIncomingVoiceCallId by rememberSaveable(conversation.id) { mutableStateOf<String?>(null) }
    var pendingVoiceCallDeleteId by rememberSaveable(conversation.id) { mutableStateOf<String?>(null) }
    var momentsVisible by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var anonymousQuestionBoxVisible by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val assistant = setting.getAssistantById(conversation.assistantId) ?: setting.getCurrentAssistant()
    val incomingVoiceCall = conversation.currentMessages
        .pendingIncomingVoiceCall()
        ?.takeUnless { it.toolCallId == handledIncomingVoiceCallId }
    val momentsEnabled = assistant.momentsEnabled
    val momentScopeId = conversation.momentScopeId(assistant)
    val anonymousQuestionBoxEnabled = assistant.anonymousQuestionBoxEnabled
    val anonymousQuestionScopeId = conversation.personaScopeId(assistant)
    val momentAssistantName = conversation.momentPersonaName(assistant)
    val momentsVM: MomentsVM = koinViewModel()
    val anonymousQuestionBoxVM: AnonymousQuestionBoxVM = koinViewModel()
    val rawMomentsUnread by remember(momentScopeId) {
        momentsVM.observeHasUnread(momentScopeId)
    }.collectAsStateWithLifecycle(false)
    val momentsUnread = momentsEnabled && rawMomentsUnread
    val rawAnonymousQuestionUnread by remember(anonymousQuestionScopeId) {
        anonymousQuestionBoxVM.observeHasUnread(anonymousQuestionScopeId)
    }.collectAsStateWithLifecycle(false)
    val anonymousQuestionUnread = anonymousQuestionBoxEnabled && rawAnonymousQuestionUnread
    val hazeState = rememberHazeState()

    TTSAutoPlay(
        vm = vm,
        setting = setting,
        conversation = conversation,
        onUpdateTtsMessage = { messageId, transform ->
            vm.updateMessage(messageId, transform)
        },
    )
    LaunchedEffect(momentsEnabled) {
        if (!momentsEnabled) {
            momentsVisible = false
        }
    }
    LaunchedEffect(anonymousQuestionBoxEnabled) {
        if (!anonymousQuestionBoxEnabled) anonymousQuestionBoxVisible = false
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(
            setting = setting,
            assistant = assistant,
            modifier = Modifier.hazeSource(hazeState),
        )
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    showCompressedMessages = showCompressedMessages,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onToggleCompressedMessages = {
                        showCompressedMessages = !showCompressedMessages
                    },
                    onCompressedSummaryChange = { newSummary ->
                        vm.updateConversation(conversation.copy(compressedSummary = newSummary))
                        vm.saveConversationAsync()
                    },
                    onSummaryEditorVisibilityChange = {
                        summaryEditorVisible = it
                    },
                    onOpenVoiceCall = {
                        voiceCallHistoryId = null
                        awaitInitialVoiceCallReply = false
                        initialVoiceCallAssistantMessageId = null
                        initialVoiceCallToolCallId = null
                        voiceCallVisible = true
                    },
                    onOpenCompressionDiagnostics = {
                        vm.recordCompressionDiagnosticSnapshot(
                            stage = "diagnostics.open",
                            details = "showCompressedMessages=$showCompressedMessages",
                        )
                        showCompressionDiagnostics = true
                    },
                    showMoments = momentsEnabled,
                    momentsUnread = momentsUnread,
                    onOpenMoments = {
                        momentsVisible = true
                    },
                    showAnonymousQuestionBox = anonymousQuestionBoxEnabled,
                    anonymousQuestionBoxUnread = anonymousQuestionUnread,
                    onOpenAnonymousQuestionBox = { anonymousQuestionBoxVisible = true },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    conversation = conversation,
                    mcpManager = vm.mcpManager,
                    hazeState = hazeState,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        vm.updateSettings(setting.copy(enableWebSearch = !enableWebSearch))
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateConversation = {
                        vm.updateConversation(it)
                        vm.saveConversationAsync()
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages, autoCompress ->
                        vm.handleCompressContext(
                            additionalPrompt,
                            targetTokens,
                            keepRecentMessages,
                            autoCompress,
                        ).also { job ->
                            scope.launch {
                                job.join()
                                if (!job.isCancelled) {
                                    val editingMessage = inputState.editingMessage
                                    val editingMessageStillVisible = editingMessage != null &&
                                        vm.conversation.value.visibleMessageNodes.any { node ->
                                            node.messages.any { message -> message.id == editingMessage }
                                        }
                                    if (editingMessage != null && !editingMessageStillVisible) {
                                        inputState.clearInput()
                                    } else {
                                        inputState.exitEditingMode()
                                    }
                                }
                            }
                        }
                    },
                    onSaveAutoCompressConfig = {
                        vm.saveAutoCompressConfig(it)
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation.forNormalChatDisplay(),
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                showCompressedMessages = showCompressedMessages,
                externalEditorVisible = summaryEditorVisible,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        val record = it.voiceCallRecord()
                        if (record?.standalone == true) {
                            pendingVoiceCallDeleteId = record.callId
                        } else {
                            vm.deleteMessage(it)
                        }
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onUpdateTtsMessage = { messageId, transform ->
                    vm.updateMessage(messageId, transform)
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onTranslateChatVoiceSegment = { message, segmentIndex, sourceText, locale ->
                    vm.translateChatVoiceSegment(message, segmentIndex, sourceText, locale)
                },
                onClearChatVoiceSegmentTranslation = { message, segmentIndex ->
                    vm.clearChatVoiceSegmentTranslation(message.id, segmentIndex)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.animateScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onOpenVoiceCallRecord = { callId ->
                    voiceCallHistoryId = callId
                    voiceCallVisible = true
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
        }
        VoiceCallOverlay(
            visible = voiceCallVisible,
            historyCallId = voiceCallHistoryId,
            awaitInitialAssistantReply = awaitInitialVoiceCallReply,
            initialAssistantMessageId = initialVoiceCallAssistantMessageId,
            initialVoiceCallToolCallId = initialVoiceCallToolCallId,
            conversation = conversation,
            userAvatar = setting.displaySetting.userAvatar,
            userName = setting.displaySetting.userNickname.ifBlank { "我" },
            assistantAvatar = assistant.avatar,
            assistantName = assistant.name.ifBlank { "AI" },
            loadingJob = loadingJob,
            hasChatModel = currentChatModel != null,
            vm = vm,
            onDismiss = {
                voiceCallVisible = false
                voiceCallHistoryId = null
                awaitInitialVoiceCallReply = false
                initialVoiceCallAssistantMessageId = null
                initialVoiceCallToolCallId = null
            },
            onVoiceCallClosed = { failureMessage, completion ->
                vm.reportVoiceCallClosed(initialVoiceCallToolCallId, failureMessage, completion)
            },
            onMessageSubmitted = {
                scope.launch {
                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                }
            }
        )
        incomingVoiceCall?.let { request ->
            IncomingVoiceCallOverlay(
                request = request,
                setting = setting,
                assistant = assistant,
                userAvatar = setting.displaySetting.userAvatar,
                userName = setting.displaySetting.userNickname.ifBlank { "我" },
                assistantAvatar = assistant.avatar,
                assistantName = assistant.name.ifBlank { "AI" },
                onAccept = {
                    handledIncomingVoiceCallId = request.toolCallId
                    awaitInitialVoiceCallReply = true
                    initialVoiceCallAssistantMessageId = request.assistantMessageId
                    initialVoiceCallToolCallId = request.toolCallId
                    voiceCallVisible = true
                    vm.handleToolApproval(
                        toolCallId = request.toolCallId,
                        approved = true,
                    )
                },
                onReject = { reason ->
                    handledIncomingVoiceCallId = request.toolCallId
                    vm.handleToolApproval(
                        toolCallId = request.toolCallId,
                        approved = false,
                        reason = reason,
                    )
                },
            )
        }
        MomentsOverlay(
            visible = momentsVisible && momentsEnabled,
            assistantId = momentScopeId,
            assistant = assistant,
            conversation = conversation,
            assistantName = momentAssistantName,
            conversationSystemPrompt = conversation.customSystemPrompt
                ?.takeIf { assistant.allowConversationSystemPrompt && it.isNotBlank() },
            settings = setting,
            vm = momentsVM,
            onDismiss = {
                momentsVisible = false
            },
        )
        AnonymousQuestionBoxOverlay(
            visible = anonymousQuestionBoxVisible && anonymousQuestionBoxEnabled,
            scopeId = anonymousQuestionScopeId,
            assistant = assistant,
            conversation = conversation,
            settings = setting,
            conversationSystemPrompt = conversation.customSystemPrompt
                ?.takeIf { assistant.allowConversationSystemPrompt && it.isNotBlank() },
            vm = anonymousQuestionBoxVM,
            onDismiss = { anonymousQuestionBoxVisible = false },
        )
    }
    ConversationCompressionDiagnosticsDialog(
        visible = showCompressionDiagnostics,
        steps = compressionDiagnostics,
        onCopy = {
            val report = buildString {
                appendLine("RikkaHub \u538b\u7f29\u6392\u67e5\u6d41\u7a0b")
                appendLine("conversation=" + conversation.id)
                append(compressionDiagnostics.joinToString("\n"))
            }
            clipboardManager?.setPrimaryClip(
                ClipData.newPlainText("\u538b\u7f29\u6392\u67e5\u6d41\u7a0b", report)
            )
            toaster.show("\u5df2\u590d\u5236\u538b\u7f29\u6392\u67e5\u6d41\u7a0b")
        },
        onClear = { vm.clearCompressionDiagnostics() },
        onDismiss = { showCompressionDiagnostics = false },
    )
    RikkaConfirmDialog(
        show = pendingVoiceCallDeleteId != null,
        title = "是否删除",
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingVoiceCallDeleteId?.let(vm::deleteVoiceCallRecord)
            pendingVoiceCallDeleteId = null
        },
        onDismiss = { pendingVoiceCallDeleteId = null },
        text = { Text("删除后将同时删除这次通话的原始对话和音频，无法撤销。") },
    )
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    showCompressedMessages: Boolean,
    onClickMenu: () -> Unit,
    onToggleCompressedMessages: () -> Unit,
    onCompressedSummaryChange: (String?) -> Unit,
    onSummaryEditorVisibilityChange: (Boolean) -> Unit,
    onOpenVoiceCall: () -> Unit,
    onOpenCompressionDiagnostics: () -> Unit,
    showMoments: Boolean,
    momentsUnread: Boolean,
    onOpenMoments: () -> Unit,
    showAnonymousQuestionBox: Boolean,
    anonymousQuestionBoxUnread: Boolean,
    onOpenAnonymousQuestionBox: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }
    val actionItems = buildList<@Composable () -> Unit> {
        add {
            IconButton(
                onClick = onOpenVoiceCall
            ) {
                Icon(HugeIcons.Voice, "Voice call")
            }
        }
        add {
            IconButton(
                onClick = onOpenCompressionDiagnostics
            ) {
                Icon(HugeIcons.Bug01, "Compression diagnostics")
            }
        }
        if (showMoments) {
            add {
                MomentsButton(
                    hasUnread = momentsUnread,
                    onClick = onOpenMoments,
                )
            }
        }
        if (showAnonymousQuestionBox) {
            add {
                AnonymousQuestionBoxButton(
                    hasUnread = anonymousQuestionBoxUnread,
                    onClick = onOpenAnonymousQuestionBox,
                )
            }
        }

        if (
            conversation.hasCompressedMessages ||
            conversation.compressedMessageNodeIds.isNotEmpty() ||
            conversation.compressedSummary?.isNotBlank() == true
        ) {
            add {
                IconButton(
                    onClick = onToggleCompressedMessages
                ) {
                    Icon(
                        imageVector = if (showCompressedMessages) HugeIcons.ViewOff else HugeIcons.View,
                        contentDescription = if (showCompressedMessages) {
                            "Hide compressed messages"
                        } else {
                            "Show compressed messages"
                        }
                    )
                }
            }
        }

        conversation.compressedSummary?.takeIf { it.isNotBlank() }?.let { summary ->
            val autoCompressConfig = settings.getAssistantById(conversation.assistantId)
                ?.let { assistant ->
                    if (assistant.allowConversationSystemPrompt) {
                        conversation.autoCompressConfig
                    } else {
                        assistant.autoCompressConfig
                    }
                }
            add {
                ConversationSummaryButton(
                    summary = summary,
                    autoCompressEnabled = autoCompressConfig?.enabled == true,
                    onSummaryChange = onCompressedSummaryChange,
                    onEditorVisibilityChange = onSummaryEditorVisibilityChange,
                )
            }
        }

        add {
            IconButton(
                onClick = onClickMenu
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }
        }
        add {
            IconButton(
                onClick = onNewChat
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        }
    }
    val actionPages = actionItems.chunked(3)
    val pagerState = rememberPagerState { actionPages.size }

    LaunchedEffect(actionPages.size) {
        if (pagerState.currentPage >= actionPages.size) {
            pagerState.scrollToPage(actionPages.lastIndex)
        }
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .width(144.dp)
                        .height(48.dp),
                ) { page ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        actionPages[page].forEach { action ->
                            action()
                        }
                    }
                }

                if (actionPages.size > 1) {
                    Row(
                        modifier = Modifier.height(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(actionPages.size) { page ->
                            Box(
                                modifier = Modifier
                                    .size(if (page == pagerState.currentPage) 6.dp else 4.dp)
                                    .background(
                                        color = if (page == pagerState.currentPage) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                        },
                                        shape = CircleShape,
                                    )
                            )
                        }
                    }
                }
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
