package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.mergeSettingsUpdate
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.voice.VoiceCallCompletion
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AutoCompressConfig
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatRequestMode
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.GenerationDoneEvent
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateDownloadState
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    var conversationInitialization by mutableStateOf<UiState<Unit>>(UiState.Loading)
        private set
    private val updateCheckRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val updateLifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            updateCheckRequests.tryEmit(Unit)
        }
    }
    val updateState = updateCheckRequests
        .flatMapLatest { updateChecker.checkUpdate() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal val voiceCallSpeech = chatService.getVoiceCallSpeechFlow(_conversationId)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val compressionDiagnostics: StateFlow<List<String>> =
        chatService.getCompressionDiagnosticsFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            try {
                chatService.initializeConversation(_conversationId)
                context.writeStringPreference("lastConversationId", _conversationId.toString())
                conversationInitialization = UiState.Success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                chatService.addError(error, _conversationId)
                conversationInitialization = UiState.Error(error)
            }
        }

        updateChecker.restoreDownloadState(context)
        ProcessLifecycleOwner.get().lifecycle.addObserver(updateLifecycleObserver)

        viewModelScope.launch {
            settingsStore.update { settings ->
                if (settings.displaySetting.showUpdates) {
                    settings
                } else {
                    settings.copy(
                        displaySetting = settings.displaySetting.copy(showUpdates = true)
                    )
                }
            }
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(updateLifecycleObserver)
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索
    val enableWebSearch = settings.map {
        it.enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<GenerationDoneEvent> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            var retiredAvatar: Avatar.Image? = null
            settingsStore.update { latest ->
                val merged = mergeSettingsUpdate(latest, newSettings)
                retiredAvatar = (latest.displaySetting.userAvatar as? Avatar.Image)
                    ?.takeIf { it != merged.displaySetting.userAvatar }
                merged
            }
            val current = settingsStore.settingsFlowRaw.first()
            retiredAvatar?.takeIf { it != current.displaySetting.userAvatar }?.let { old ->
                val referenced = current.assistants.any { assistant ->
                    (assistant.avatar as? Avatar.Image)?.url == old.url || assistant.background == old.url || assistant.voiceCallBackground == old.url
                }
                if (!referenced) filesManager.deleteChatFiles(listOf(old.url.toUri()))
            }
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    val updateDownloadState: StateFlow<UpdateDownloadState?> = updateChecker.downloadState

    fun retryUpdateCheck() {
        updateCheckRequests.tryEmit(Unit)
    }

    fun retryUpdateDownload(failure: UpdateDownloadState.Failed) {
        updateChecker.retryDownload(context, failure)
    }

    fun clearUpdateDownloadState() {
        updateChecker.clearDownloadState(context)
    }

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(
        content: List<UIMessagePart>,
        answer: Boolean = true,
        requestMode: ChatRequestMode = ChatRequestMode.Normal,
        includeVoiceCallConnectedEvent: Boolean = false,
        requestMessagePartsProvider: (suspend () -> List<UIMessagePart>)? = null,
    ) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        chatService.sendMessage(
            conversationId = _conversationId,
            content = content,
            answer = answer,
            requestMode = requestMode,
            includeVoiceCallConnectedEvent = includeVoiceCallConnectedEvent,
            requestMessagePartsProvider = requestMessagePartsProvider,
        )
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int,
        autoCompress: Boolean,
    ): Job {
        return viewModelScope.launch {
            val autoCompressConfig = AutoCompressConfig(
                enabled = autoCompress,
                additionalPrompt = additionalPrompt,
                targetTokens = targetTokens,
                keepRecentMessages = keepRecentMessages.coerceAtLeast(1),
            )
            val assistant = settings.value.getAssistantById(conversation.value.assistantId)
            val conversationScoped = assistant?.allowConversationSystemPrompt == true
            chatService.compressConversation(
                _conversationId,
                additionalPrompt,
                targetTokens,
                keepRecentMessages,
                if (conversationScoped) autoCompressConfig else null
            ).onSuccess {
                saveAutoCompressConfigInternal(autoCompressConfig)
            }.onFailure {
                chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
            }
        }
    }


    fun recordCompressionDiagnosticSnapshot(stage: String, details: String? = null) {
        chatService.recordCompressionDiagnosticSnapshot(_conversationId, stage, details)
    }

    fun clearCompressionDiagnostics() {
        chatService.clearCompressionDiagnostics(_conversationId)
    }

    fun saveAutoCompressConfig(config: AutoCompressConfig) {
        viewModelScope.launch {
            saveAutoCompressConfigInternal(config)
        }
    }

    private suspend fun saveAutoCompressConfigInternal(config: AutoCompressConfig) {
        val currentConversation = conversation.value
        val assistant = settings.value.getAssistantById(currentConversation.assistantId)
            ?: return
        if (assistant.allowConversationSystemPrompt) {
            chatService.mutateConversation(_conversationId) { it.copy(autoCompressConfig = config) }
        } else {
            settingsStore.update { currentSettings ->
                currentSettings.copy(
                    assistants = currentSettings.assistants.map { item ->
                        if (item.id == assistant.id) {
                            item.copy(autoCompressConfig = config)
                        } else {
                            item
                        }
                    }
                )
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun deleteVoiceCallRecord(callId: String) {
        viewModelScope.launch {
            chatService.deleteVoiceCallRecord(_conversationId, callId)
        }
    }


    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    suspend fun reportVoiceCallClosed(toolCallId: String?, failureMessage: String? = null, voiceCallCompletion: VoiceCallCompletion? = null) {
        chatService.reportVoiceCallClosed(_conversationId, toolCallId, failureMessage, voiceCallCompletion).join()
    }

    fun cancelGeneration(): Job? = chatService.cancelGeneration(_conversationId)

    fun stopGeneration() {
        val messageId = conversation.value.messageNodes.lastOrNull()?.currentMessage?.id
        val job = chatService.cancelGeneration(_conversationId) ?: return
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId, expectedJob = job, expectedMessageId = messageId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.mutateConversation(_conversationId) { it.copy(title = title) }
        }
    }

    suspend fun deleteConversation(conversation: Conversation) {
        chatService.deleteConversation(conversation)
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.mutateConversation(conversation.id) { it.copy(isPinned = !it.isPinned) }
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            chatService.mutateConversation(conversation.id) { it.copy(assistantId = targetAssistantId) }
            if (conversation.id == _conversationId) settingsStore.updateAssistant(targetAssistantId)
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun translateVoiceCallBubble(
        message: UIMessage,
        bubbleKey: String,
        sourceText: String,
        targetLanguage: Locale,
    ) {
        chatService.translateVoiceCallBubble(
            conversationId = _conversationId,
            message = message,
            bubbleKey = bubbleKey,
            sourceText = sourceText,
            targetLanguage = targetLanguage,
        )
    }

    fun translateChatVoiceSegment(
        message: UIMessage,
        segmentIndex: Int,
        sourceText: String,
        targetLanguage: Locale,
    ) {
        chatService.translateChatVoiceSegment(
            conversationId = _conversationId,
            message = message,
            segmentIndex = segmentIndex,
            sourceText = sourceText,
            targetLanguage = targetLanguage,
        )
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun clearVoiceCallTranslation(messageId: Uuid, bubbleKey: String, clearLegacyTranslation: Boolean) {
        chatService.clearVoiceCallTranslation(
            conversationId = _conversationId,
            messageId = messageId,
            bubbleKey = bubbleKey,
            clearLegacyTranslation = clearLegacyTranslation,
        )
    }

    fun clearChatVoiceSegmentTranslation(messageId: Uuid, segmentIndex: Int) {
        chatService.clearChatVoiceSegmentTranslation(
            conversationId = _conversationId,
            messageId = messageId,
            segmentIndex = segmentIndex,
        )
    }

    fun updateConversation(update: (Conversation) -> Conversation) {
        viewModelScope.launch { chatService.mutateConversation(_conversationId, update) }
    }

    fun updateMessage(messageId: Uuid, transform: (UIMessage) -> UIMessage) {
        updateConversation { currentConversation ->
            currentConversation.copy(
                messageNodes = currentConversation.messageNodes.map { node ->
                    if (node.messages.any { it.id == messageId }) {
                        node.copy(
                            messages = node.messages.map { message ->
                                if (message.id == messageId) transform(message) else message
                            }
                        )
                    } else {
                        node
                    }
                }
            )
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
