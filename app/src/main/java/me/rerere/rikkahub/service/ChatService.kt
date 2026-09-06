package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.buildVoiceCallAudioTagPrompt
import me.rerere.rikkahub.data.ai.prompts.buildVoiceCallAudioTaggingRequest
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.REQUEST_VOICE_CALL_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.datastore.CompressOpenAIConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.local.LocalBuildIntegration
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.voice.VOICE_CALL_UNAVAILABLE_MESSAGE
import me.rerere.rikkahub.data.voice.ChatVoiceReplyMaterializer
import me.rerere.rikkahub.data.voice.inspectChatVoiceReplyMaterialization
import me.rerere.rikkahub.data.voice.toDiagnosticDetails
import me.rerere.rikkahub.data.voice.chatVoiceReply
import me.rerere.rikkahub.data.voice.updateChatVoiceReplySegment
import me.rerere.rikkahub.data.voice.VoiceCallCompletion
import me.rerere.rikkahub.data.voice.isStandaloneVoiceCallRecord
import me.rerere.rikkahub.data.voice.voiceCallRecord
import me.rerere.rikkahub.data.voice.voiceCallRecordNodeIdsFullyCoveredBy
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagFormat
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagSelectionResult
import me.rerere.rikkahub.data.voice.VoiceCallTaggingFallbackReason
import me.rerere.rikkahub.data.voice.consumePendingVoiceCallEndedEvent
import me.rerere.rikkahub.data.voice.createVoiceCallAudioTagSelectionTool
import me.rerere.rikkahub.data.voice.splitVoiceCallAudioTaggingSegments
import me.rerere.rikkahub.data.voice.selectVoiceCallAudioTaggingSegmentIndexes
import me.rerere.rikkahub.data.voice.parseVoiceCallAudioTagResponse
import me.rerere.rikkahub.data.voice.voiceCallAudioTagFormatOrNull
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagAssignment
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagMode
import me.rerere.rikkahub.data.voice.forVoiceCallProvider
import me.rerere.rikkahub.data.voice.VoiceCallTagSelectionSource
import me.rerere.rikkahub.data.voice.withSelectedVoiceCallAudioTagAssignments
import me.rerere.rikkahub.data.voice.voiceCallAudioTagAssignmentsOrEmpty
import me.rerere.rikkahub.data.voice.withIncrementalVoiceCallAudioTagAssignments
import me.rerere.rikkahub.data.voice.withoutVoiceCallAudioTagsForNormalContext
import me.rerere.rikkahub.data.voice.sanitizeVoiceCallTextForTranslation
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AutoCompressConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.momentScopeId
import me.rerere.rikkahub.data.model.messagesForGeneration
import me.rerere.rikkahub.data.model.personaScopeId
import me.rerere.rikkahub.data.model.MemoryScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MomentAuthor
import me.rerere.rikkahub.data.repository.MomentEntry
import me.rerere.rikkahub.data.repository.MomentRepository
import me.rerere.rikkahub.data.repository.AnonymousQuestionRepository
import me.rerere.rikkahub.data.repository.AnonymousQuestionEntry
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.utils.cancelNotification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val STREAMING_UI_UPDATE_INTERVAL_NANOS = 50_000_000L
private const val MIN_COMPRESSION_CHUNK_TOKENS = 8000
private const val COMPRESSION_CHUNK_TOKENS_PER_TARGET_TOKEN = 8
private const val VOICE_CALL_SYSTEM_PROMPT_COMMON = """
你正在语音通话模式中回复用户。
不要再次发起、邀请、请求或切换到另一通语音通话，也不要调用打电话工具。
如果用户的表达暗示想打电话，只需在当前通话中继续回应，不要把它当成新的拨号请求。
请像真实电话聊天一样自然、简短、连贯地说话。
每一句都尽量短，适合一句一句朗读。
每一句都必须用句号、问号或感叹号结束。
只回答当前最需要回应的内容，通常使用一个短段落；内容已经完整时立即结束，不要为了凑长度继续展开。
不使用 Markdown 表格，不写长列表。
如果需要解释复杂问题，分成几个容易朗读的小块。
一次最多问用户一个问题。
不要使用任何表情、emoji 或颜文字。
不要输出贴纸、表情包、颜文字、ASCII 表情或类似“(≧▽≦)”的符号组合。
任何 emoji、颜文字、表情包文本都会被系统硬性删除。
"""

private val VOICE_CALL_ACTIVE_TOOL_STATUS = """
    Voice-call state: ACTIVE. The call is connected.
    Follow the current voice-call system instructions for audio-tag output. Do not invent a second
    tag policy in this tool result.
""".trimIndent()

private val VOICE_CALL_ENDED_TOOL_STATUS = """
    Voice-call state: ENDED. The call has been disconnected.
    The call-specific audio-tag policy no longer applies after hangup.
""".trimIndent()

private const val PROACTIVE_VOICE_CALL_SYSTEM_PROMPT = """
你可以使用 request_voice_call 工具主动邀请用户进行语音通话。
只在实时说话明显比继续打字更自然、更有帮助时发起来电，不要频繁使用，也不要为了制造效果而来电。
调用时给出一句简短、自然的来电理由，不要在正文里假装电话已经接通。
如果用户接听，立即用一句简短自然的话开始通话，并继续遵守语音通话的简短口语风格。
如果用户拒接或未接，尊重结果，不要立刻再次发起，也不要责备或施压。
"""

private const val ANONYMOUS_QUESTION_SYSTEM_PROMPT = """
    Anonymous question-box rules:
    All questions and answers in the anonymous question box are anonymous.
    Do not infer, identify, name, or claim to know who asked a question or wrote an answer.
    When you publish an anonymous question, do not reveal your identity, persona name, or that you authored it.
    Never include identity clues in an anonymous question, answer, or comment.
"""

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

data class GenerationDoneEvent(
    val conversationId: Uuid,
    val requestMode: ChatRequestMode,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val momentRepository: MomentRepository,
    private val anonymousQuestionRepository: AnonymousQuestionRepository,
    private val chatVoiceReplyMaterializer: ChatVoiceReplyMaterializer,
) {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)
    private val compressionDiagnostics = ConversationCompressionDiagnosticsRecorder()

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<GenerationDoneEvent>()
    val generationDoneFlow: SharedFlow<GenerationDoneEvent> = _generationDoneFlow.asSharedFlow()

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch(Dispatchers.IO) {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getCompressionDiagnosticsFlow(conversationId: Uuid): StateFlow<List<String>> =
        compressionDiagnostics.observe(conversationId)

    fun clearCompressionDiagnostics(conversationId: Uuid) {
        compressionDiagnostics.clear(conversationId)
    }

    fun recordCompressionDiagnosticSnapshot(
        conversationId: Uuid,
        stage: String,
        details: String? = null,
    ) {
        compressionDiagnostics.record(
            conversationId = conversationId,
            stage = stage,
            conversation = getConversationFlow(conversationId).value,
            details = details,
        )
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val initializedConversation = session.initializeState {
            val conversation = conversationRepo.getConversationById(conversationId)
            if (conversation != null) {
                settingsStore.updateAssistant(conversation.assistantId)
                conversation
            } else {
                // 新建对话, 并添加预设消息
                val currentSettings = settingsStore.settingsFlowRaw.first()
                val assistant = currentSettings.getCurrentAssistant()
                Conversation.ofId(
                    id = conversationId,
                    assistantId = assistant.id,
                    newConversation = true
                ).updateCurrentMessages(assistant.presetMessages)
            }
        }
        compressionDiagnostics.record(
            conversationId = conversationId,
            stage = "initialize.ready",
            conversation = initializedConversation,
        )
    }
    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        requestMode: ChatRequestMode = ChatRequestMode.Normal,
        includeVoiceCallConnectedEvent: Boolean = false,
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch(Dispatchers.IO) {
            try {
                initializeConversation(conversationId)
                val currentConversation = session.state.value
                val endedEventConsumption = if (
                    answer && requestMode == ChatRequestMode.Normal
                ) {
                    currentConversation.consumePendingVoiceCallEndedEvent()
                } else {
                    null
                }
                val conversationBeforeSend = endedEventConsumption?.conversation ?: currentConversation
                compressionDiagnostics.record(
                    conversationId = conversationId,
                    stage = "send.before",
                    conversation = conversationBeforeSend,
                    details = "answer=$answer requestMode=$requestMode",
                )
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(conversationBeforeSend.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = processedContent,
                )
                val newConversation = conversationBeforeSend.copy(
                    messageNodes = conversationBeforeSend.messageNodes + userMessage.toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                LocalBuildIntegration.onUserMessageSent(
                    context = context,
                    message = userMessage,
                    assistantId = conversationBeforeSend.assistantId,
                )
                compressionDiagnostics.record(
                    conversationId = conversationId,
                    stage = "send.saved",
                    conversation = getConversationFlow(conversationId).value,
                )

                // 开始补全
                if (answer) {
                    autoCompressConversationIfNeeded(
                        conversationId = conversationId,
                        conversation = getConversationFlow(conversationId).value
                    )
                    val voiceCallRuntimeState = if (endedEventConsumption?.shouldNotifyModel == true) {
                        VoiceCallRuntimeState.ENDED
                    } else {
                        requestMode.defaultVoiceCallRuntimeState()
                    }
                    val voiceCallUserEventState = when {
                        endedEventConsumption?.shouldNotifyModel == true -> VoiceCallRuntimeState.ENDED
                        includeVoiceCallConnectedEvent && requestMode == ChatRequestMode.VoiceCall ->
                            VoiceCallRuntimeState.ACTIVE

                        else -> null
                    }
                    handleMessageComplete(
                        conversationId = conversationId,
                        requestMode = requestMode,
                        voiceCallRuntimeState = voiceCallRuntimeState,
                        voiceCallUserEventState = voiceCallUserEventState,
                    )
                }

                _generationDoneFlow.emit(
                    GenerationDoneEvent(
                        conversationId = conversationId,
                        requestMode = requestMode,
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch(Dispatchers.IO) {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(
                    GenerationDoneEvent(
                        conversationId = conversationId,
                        requestMode = ChatRequestMode.Normal,
                    )
                )
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch(Dispatchers.IO) {
            try {
                val conversation = session.state.value
                val acceptedVoiceCall = approved && answer == null &&
                    conversation.messageNodes.any { node ->
                        node.messages.any { message ->
                            message.parts.any { part ->
                                part is UIMessagePart.Tool &&
                                    part.toolCallId == toolCallId &&
                                    part.toolName == REQUEST_VOICE_CALL_TOOL_NAME
                            }
                        }
                    }
                val resumeRequestMode = if (acceptedVoiceCall) {
                    ChatRequestMode.VoiceCall
                } else {
                    ChatRequestMode.Normal
                }
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }
                val connectedVoiceCallOutput = if (acceptedVoiceCall) {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("status", "connected")
                                put(
                                    "message",
                                    VOICE_CALL_ACTIVE_TOOL_STATUS,
                                )
                            }.toString()
                        )
                    )
                } else {
                    null
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(
                                                approvalState = newApprovalState,
                                                output = connectedVoiceCallOutput ?: part.output,
                                            )
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(
                        conversationId = conversationId,
                        requestMode = resumeRequestMode,
                    )
                }

                _generationDoneFlow.emit(
                    GenerationDoneEvent(
                        conversationId = conversationId,
                        requestMode = resumeRequestMode,
                    )
                )
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    fun reportVoiceCallClosed(
        conversationId: Uuid,
        toolCallId: String? = null,
        failureMessage: String? = null,
        voiceCallCompletion: VoiceCallCompletion? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch(Dispatchers.IO) {
            try {
                runCatching { previousJob?.join() }

                // Audio tags are call-only speech metadata. Preserve the primary text while
                // removing the selected branch's tags before it returns to normal chat.
                var conversation = session.state.value.let { current ->
                    current.updateCurrentMessages(
                        current.currentMessages.map(UIMessage::withoutVoiceCallAudioTagsForNormalContext)
                    )
                }
                val completedCallMessages = voiceCallCompletion?.let { completion ->
                    conversation.currentMessages.filter { message ->
                        message.id.toString() in completion.messageIds &&
                            (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT)
                    }
                }.orEmpty()
                val hasVoiceCallConversation = completedCallMessages.any { it.toText().isNotBlank() }
                voiceCallCompletion?.let { completion ->
                    val hasAiContent = completedCallMessages.any { message ->
                        message.role == MessageRole.ASSISTANT && message.toText().isNotBlank()
                    }
                    if (hasAiContent) {
                        val recordNode = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            annotations = listOf(
                                UIMessageAnnotation.VoiceCallRecord(
                                    callId = completion.callId,
                                    durationSeconds = completion.durationSeconds,
                                    cardAnchor = true,
                                    standalone = true,
                                    messageIds = completion.messageIds,
                                    audioSegmentsByMessageId = completion.audioSegmentsByMessageId,
                                    pendingEndedEvent = hasVoiceCallConversation,
                                )
                            ),
                        ).toMessageNode()
                        conversation = conversation.copy(
                            messageNodes = conversation.messageNodes + recordNode
                        )
                    }
                }
                if (toolCallId != null) {
                    val target = conversation.messageNodes.mapIndexedNotNull { nodeIndex, node ->
                        node.messages.mapIndexedNotNull { messageIndex, message ->
                            messageIndex.takeIf {
                                message.parts.any { part ->
                                    part is UIMessagePart.Tool &&
                                        part.toolCallId == toolCallId &&
                                        part.toolName == REQUEST_VOICE_CALL_TOOL_NAME
                                }
                            }?.let { messageIndex -> nodeIndex to messageIndex }
                        }.firstOrNull()
                    }.firstOrNull()

                    if (target != null) {
                        val (targetNodeIndex, targetMessageIndex) = target
                        val result = buildJsonObject {
                            put("success", failureMessage == null)
                            put("status", if (failureMessage == null) "ended" else "failed")
                            put("message", VOICE_CALL_ENDED_TOOL_STATUS)
                            if (failureMessage != null) {
                                put("error", failureMessage)
                            }
                        }.toString()
                        val updatedNodes = conversation.messageNodes.mapIndexed { nodeIndex, node ->
                            if (nodeIndex != targetNodeIndex) {
                                node
                            } else {
                                node.copy(
                                    messages = node.messages.mapIndexed { messageIndex, message ->
                                        if (messageIndex != targetMessageIndex) {
                                            message
                                        } else {
                                            message.copy(
                                                parts = message.parts.map { part ->
                                                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                                                        part.copy(output = listOf(UIMessagePart.Text(result)))
                                                    } else {
                                                        part
                                                    }
                                                }
                                            )
                                        }
                                    },
                                    selectIndex = targetMessageIndex,
                                )
                            }
                        }
                        conversation = conversation.copy(messageNodes = updatedNodes)
                    }
                }
                saveConversation(conversationId, conversation)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_voice_call))
            }
        }
        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        requestMode: ChatRequestMode = ChatRequestMode.Normal,
        voiceCallRuntimeState: VoiceCallRuntimeState = requestMode.defaultVoiceCallRuntimeState(),
        voiceCallUserEventState: VoiceCallRuntimeState? = null,
        additionalSystemPrompt: String? = null,
        allowVoiceCallAudioTags: Boolean = true,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))


            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            val voiceCallRuntimeContext = buildVoiceCallRuntimeContext(voiceCallRuntimeState)
            val voiceCallAudioTagFormat = settings.getSelectedTTSProvider()
                ?.voiceCallAudioTagFormatOrNull()
                ?.takeIf {
                    allowVoiceCallAudioTags && requestMode == ChatRequestMode.VoiceCall
                }
            val voiceCallAudioTagMode = settings.voiceCallAudioTagMode.forVoiceCallProvider(
                settings.getSelectedTTSProvider(),
            )

            // start generating
            val session = getOrCreateSession(conversationId)
            val generationMessages = conversation.messagesForGeneration(messageRange).let { messages ->
                when (requestMode) {
                    ChatRequestMode.Normal ->
                        messages.map(UIMessage::withoutVoiceCallAudioTagsForNormalContext)

                    // Voice-call directions are injected through extraSystemPrompt below.
                    // Keep persisted/UI messages untouched so temporary protocol text
                    // can never leak into the user's bubble.
                    ChatRequestMode.VoiceCall -> messages
                }
            }
            val generationBaseMessageIds = conversation.currentMessages.mapTo(mutableSetOf()) { it.id }
            val transientLastContextMessage = voiceCallUserEventState?.let { eventState ->
                generationMessages.lastOrNull()
                    ?.takeIf { it.role == MessageRole.USER }
                    ?.withVoiceCallRuntimeEventForRequest(eventState)
            }
            val voiceCallToolEnabled = LocalToolOption.VoiceCall in assistant.localTools &&
                voiceCallRuntimeState == VoiceCallRuntimeState.INACTIVE
            val voiceCallConfigured = settings.getSelectedTTSProvider() != null
            val localToolOptions = assistant.localTools.filterNot {
                (voiceCallRuntimeState != VoiceCallRuntimeState.INACTIVE && it == LocalToolOption.VoiceCall) ||
                    (it == LocalToolOption.Tts && (requestMode != ChatRequestMode.Normal || !voiceCallConfigured))
            }
            val proactiveVoiceCallEnabled = voiceCallToolEnabled
            val momentScopeId = conversation.momentScopeId(assistant)
            val anonymousQuestionScopeId = conversation.personaScopeId(assistant)
            val anonymousQuestionContextPrompt = when {
                requestMode == ChatRequestMode.Normal && assistant.anonymousQuestionBoxEnabled ->
                    buildAnonymousQuestionContextPromptIfNeeded(anonymousQuestionScopeId, generationMessages)
                else -> null
            }
            val momentContextPrompt = when {
                requestMode == ChatRequestMode.Normal && assistant.momentsEnabled -> buildMomentContextPromptIfNeeded(
                    assistantId = momentScopeId,
                    messages = generationMessages,
                )

                else -> null
            }
            var voiceCallFailureReported = false
            val generationFlow = generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = generationMessages,
                assistant = assistant,
                conversationId = conversation.id,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationContextSummary = conversation.compressedSummary,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                runtimeStateSystemPrompt = voiceCallRuntimeContext.systemPrompt,
                transientLastContextMessage = transientLastContextMessage,
                extraSystemPrompt = listOfNotNull(
                    when (requestMode) {
                        ChatRequestMode.Normal -> null
                        ChatRequestMode.VoiceCall -> listOf(
                            VOICE_CALL_SYSTEM_PROMPT_COMMON.trimIndent(),
                            buildVoiceCallAudioTagPrompt(voiceCallAudioTagMode, voiceCallAudioTagFormat),
                        ).joinToString("\n\n")
                    },
                    PROACTIVE_VOICE_CALL_SYSTEM_PROMPT.trimIndent().takeIf { proactiveVoiceCallEnabled },
                    momentContextPrompt,
                    ANONYMOUS_QUESTION_SYSTEM_PROMPT.takeIf {
                        requestMode == ChatRequestMode.Normal && assistant.anonymousQuestionBoxEnabled
                    },
                    anonymousQuestionContextPrompt,
                    additionalSystemPrompt,
                ).joinToString("\n\n").takeIf { it.isNotBlank() },
                memories = buildList {
                    // 本地记忆工具记录统一属于助手，所有会话都读取同一份记忆。
                    addAll(memoryRepository.getMemories(MemoryScope.assistant(assistant.id)))
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (settings.enableWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(
                        localTools.getTools(
                            options = localToolOptions,
                            usageLockEnabled = settings.usageReminderConfig.lockEnabled,
                            voiceCallConfigured = voiceCallConfigured,
                            momentAssistantId = when {
                                requestMode == ChatRequestMode.Normal && assistant.momentsEnabled -> momentScopeId
                                else -> null
                            },
                            anonymousQuestionScopeId = when {
                                requestMode == ChatRequestMode.Normal && assistant.anonymousQuestionBoxEnabled -> anonymousQuestionScopeId
                                else -> null
                            },
                            includeBuildTools = requestMode == ChatRequestMode.Normal,
                            buildToolAssistantId = assistant.id,
                        )
                    )
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
                        add(
                            Tool(
                                name = "mcp__" + tool.name,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = tool.needsApproval,
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
            )
            var latestPrimaryMessages: List<UIMessage>? = null
            val incrementalVoiceCallTagging =
                requestMode == ChatRequestMode.VoiceCall &&
                    voiceCallAudioTagMode == VoiceCallAudioTagMode.SECOND_PASS &&
                    voiceCallAudioTagFormat != null
            val tagAssignmentsByMessageId = mutableMapOf<kotlin.uuid.Uuid, MutableMap<Int, VoiceCallAudioTagAssignment?>>()
            val nextTagIndexByMessageId = mutableMapOf<kotlin.uuid.Uuid, Int>()
            val tagProjectionLock = Any()
            var streamingMessageId: kotlin.uuid.Uuid? = null
            var streamingNodeIndex: Int? = null
            var lastStreamingUiUpdateNanos = 0L

            coroutineScope {
                val tagJobs = mutableListOf<Job>()
                fun enqueueVoiceCallTagging(messages: List<UIMessage>, includeUnfinishedTail: Boolean) {
                    if (!incrementalVoiceCallTagging) return
                    val primaryReply = messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.toText().isNotBlank() }
                        ?: return
                    val segments = splitVoiceCallAudioTaggingSegments(primaryReply.toText().trim())
                    val lastEligibleIndex = if (includeUnfinishedTail) {
                        segments.lastIndex
                    } else {
                        segments.indexOfLast { segment ->
                            segment.lastOrNull() in setOf('。', '.', '！', '!', '？', '?', '；', ';', '\n')
                        }
                    }
                    if (lastEligibleIndex < 0) return
                    val nextIndex = synchronized(tagProjectionLock) {
                        nextTagIndexByMessageId[primaryReply.id] ?: 0
                    }
                    if (nextIndex > lastEligibleIndex) return
                    for (index in nextIndex..lastEligibleIndex) {
                        val sentence = segments.getOrNull(index) ?: continue
                        synchronized(tagProjectionLock) {
                            nextTagIndexByMessageId[primaryReply.id] = index + 1
                        }
                        tagJobs += launch {
                            val assignmentResult = try {
                                Result.success(
                                    tagVoiceCallSentence(
                                        settings = settings,
                                        model = model,
                                        assistant = assistant,
                                        processingStatus = session.processingStatus,
                                        primaryReply = primaryReply,
                                        sentence = sentence,
                                        format = voiceCallAudioTagFormat!!,
                                    )
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Result.failure(error)
                            }
                            if (assignmentResult.isFailure) {
                                Logging.log(TAG, "sentence voice-call tagging failed; using no-tag fallback: ${assignmentResult.exceptionOrNull()}")
                            }
                            synchronized(tagProjectionLock) {
                                tagAssignmentsByMessageId
                                    .getOrPut(primaryReply.id) { mutableMapOf() }[index] = assignmentResult.getOrNull()
                                val currentConversation = getConversationFlow(conversationId).value
                                val currentReply = currentConversation.currentMessages
                                    .firstOrNull { it.id == primaryReply.id }
                                if (currentReply != null) {
                                    val projectedReply = currentReply.withIncrementalVoiceCallAudioTagAssignments(
                                        assignments = tagAssignmentsByMessageId.getValue(primaryReply.id),
                                        format = voiceCallAudioTagFormat,
                                    )
                                    updateConversation(
                                        conversationId,
                                        currentConversation.updateMessageAtNodeIndex(
                                            nodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
                                                node.messages.any { it.id == projectedReply.id }
                                            }.takeIf { it >= 0 },
                                            message = projectedReply,
                                        ),
                                        checkFiles = false,
                                    )
                                }
                            }
                        }
                    }
                }

                generationFlow.onCompletion {
                    // 取消 Live Update 通知
                    cancelLiveUpdateNotification(conversationId)

                    // 可能被取消了，或者意外结束，兜底更新
                    val currentConversation = getConversationFlow(conversationId).value
                    val finalStreamMessage = latestPrimaryMessages?.lastOrNull()?.let { message ->
                        synchronized(tagProjectionLock) {
                            tagAssignmentsByMessageId[message.id]?.let { assignments ->
                                message.withIncrementalVoiceCallAudioTagAssignments(
                                    assignments = assignments,
                                    format = voiceCallAudioTagFormat!!,
                                )
                            } ?: message
                        }
                    }
                    val conversationBeforeFinish = if (finalStreamMessage != null) {
                        val nodeIndex = if (streamingMessageId == finalStreamMessage.id) {
                            streamingNodeIndex
                        } else {
                            currentConversation.visibleMessageNodeIndexAt(
                                latestPrimaryMessages!!.lastIndex,
                            )
                        }
                        currentConversation.updateMessageAtNodeIndex(
                            nodeIndex = nodeIndex,
                            message = finalStreamMessage,
                        )
                    } else {
                        currentConversation
                    }
                    val updatedConversation = conversationBeforeFinish.copy(
                        messageNodes = conversationBeforeFinish.messageNodes.map { node ->
                            node.copy(messages = node.messages.map { it.finishReasoning() })
                        },
                        updateAt = Instant.now()
                    )
                    updateConversation(conversationId, updatedConversation)

                    // Show notification if app is not in foreground
                    if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                        sendGenerationDoneNotification(conversationId, senderName)
                    }
                }.collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> {
                            val chunkMessages = chunk.messages
                            latestPrimaryMessages = chunkMessages
                            val projectedMessages = synchronized(tagProjectionLock) {
                                if (!incrementalVoiceCallTagging) {
                                    chunkMessages
                                } else {
                                    chunkMessages.map { message ->
                                        tagAssignmentsByMessageId[message.id]?.let { assignments ->
                                            message.withIncrementalVoiceCallAudioTagAssignments(
                                                assignments = assignments,
                                                format = voiceCallAudioTagFormat!!,
                                            )
                                        } ?: message
                                    }
                                }
                            }
                            synchronized(tagProjectionLock) {
                                val currentConversation = getConversationFlow(conversationId).value
                                val streamedMessage = projectedMessages.lastOrNull()
                                val now = System.nanoTime()
                                val shouldUpdateUi = streamedMessage != null && (
                                    streamingMessageId != streamedMessage.id ||
                                        now - lastStreamingUiUpdateNanos >= STREAMING_UI_UPDATE_INTERVAL_NANOS
                                    )
                                if (shouldUpdateUi) {
                                    val nodeIndex = if (streamingMessageId == streamedMessage.id) {
                                        streamingNodeIndex
                                    } else {
                                        currentConversation.visibleMessageNodeIndexAt(projectedMessages.lastIndex)
                                    }
                                    val updatedConversation = currentConversation.updateMessageAtNodeIndex(
                                        nodeIndex = nodeIndex,
                                        message = streamedMessage,
                                    )
                                    streamingMessageId = streamedMessage.id
                                    streamingNodeIndex = nodeIndex ?: updatedConversation.messageNodes.lastIndex
                                    lastStreamingUiUpdateNanos = now
                                    updateConversation(
                                        conversationId,
                                        updatedConversation,
                                        checkFiles = false,
                                    )
                                }
                            }
                            enqueueVoiceCallTagging(chunkMessages, includeUnfinishedTail = false)

                            if (!voiceCallFailureReported && requestMode == ChatRequestMode.Normal && chunkMessages.any { message ->
                                    message.parts.any { part ->
                                        part is UIMessagePart.Tool &&
                                            part.toolName == REQUEST_VOICE_CALL_TOOL_NAME &&
                                            part.output.any { output ->
                                                output is UIMessagePart.Text &&
                                                    output.text.contains(VOICE_CALL_UNAVAILABLE_MESSAGE)
                                            }
                                    }
                                }) {
                                voiceCallFailureReported = true
                                addError(
                                    IllegalStateException(VOICE_CALL_UNAVAILABLE_MESSAGE),
                                    conversationId,
                                    title = context.getString(R.string.error_title_voice_call),
                                )
                            }

                            // 如果应用不在前台，发送 Live Update 通知
                            if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                                sendLiveUpdateNotification(conversationId, chunkMessages, senderName)
                            }
                        }
                    }
                }

                if (incrementalVoiceCallTagging) {
                    enqueueVoiceCallTagging(
                        getConversationFlow(conversationId).value.currentMessages,
                        includeUnfinishedTail = true,
                    )
                }
                tagJobs.forEach { it.join() }
                if (incrementalVoiceCallTagging) {
                    synchronized(tagProjectionLock) {
                        val currentConversation = getConversationFlow(conversationId).value
                        val projectedMessages = currentConversation.currentMessages.map { message ->
                            tagAssignmentsByMessageId[message.id]?.let { assignments ->
                                message.withIncrementalVoiceCallAudioTagAssignments(
                                    assignments = assignments,
                                    format = voiceCallAudioTagFormat!!,
                                )
                            } ?: message
                        }
                        updateConversation(
                            conversationId,
                            currentConversation.updateCurrentMessages(projectedMessages),
                        )
                    }
                }
            }

            if (!incrementalVoiceCallTagging && voiceCallAudioTagMode == VoiceCallAudioTagMode.SECOND_PASS && voiceCallAudioTagFormat != null) {
                val primaryMessages = latestPrimaryMessages
                    ?: getConversationFlow(conversationId).value.currentMessages
                val taggedMessages = applySecondPassVoiceCallAudioTags(
                    settings = settings,
                    model = model,
                    assistant = assistant,
                    processingStatus = session.processingStatus,
                    primaryMessages = primaryMessages,
                    format = voiceCallAudioTagFormat,
                )
                val taggedConversation = getConversationFlow(conversationId).value
                    .updateCurrentMessages(taggedMessages)
                updateConversation(conversationId, taggedConversation)
            }

            if (requestMode == ChatRequestMode.Normal) {
                compressionDiagnostics.record(
                    conversationId = conversationId,
                    stage = "voice_reply.materialize.before",
                    conversation = getConversationFlow(conversationId).value,
                    details = "generationBaseMessages=${generationBaseMessageIds.size}",
                )
                val materializationInspection = inspectChatVoiceReplyMaterialization(
                    conversation = getConversationFlow(conversationId).value,
                    generationBaseMessageIds = generationBaseMessageIds,
                )
                compressionDiagnostics.record(
                    conversationId = conversationId,
                    stage = "voice_reply.materialize.target",
                    conversation = getConversationFlow(conversationId).value,
                    details = materializationInspection.toDiagnosticDetails(),
                )
                try {
                    chatVoiceReplyMaterializer.materialize(
                        conversation = getConversationFlow(conversationId).value,
                        generationBaseMessageIds = generationBaseMessageIds,
                        settings = settings,
                        onUpdate = { updateConversation(conversationId, it) },
                    )
                } catch (error: Throwable) {
                    if (error !is CancellationException) {
                        compressionDiagnostics.record(
                            conversationId = conversationId,
                            stage = "voice_reply.materialize.exception",
                            conversation = getConversationFlow(conversationId).value,
                            details = "type=${error.javaClass.simpleName}",
                        )
                    }
                    throw error
                }
                compressionDiagnostics.record(
                    conversationId = conversationId,
                    stage = "voice_reply.materialize.after",
                    conversation = getConversationFlow(conversationId).value,
                )
            }
        }.onFailure {
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            if (it is CancellationException) {
                return@onFailure
            }

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun tagVoiceCallSentence(
        settings: Settings,
        model: Model,
        assistant: Assistant,
        processingStatus: MutableStateFlow<String?>,
        primaryReply: UIMessage,
        sentence: String,
        format: VoiceCallAudioTagFormat,
    ): VoiceCallAudioTagAssignment? {
        val sentenceMessage = primaryReply.copy(
            parts = listOf(UIMessagePart.Text(sentence)),
        )
        val taggedMessage = applySecondPassVoiceCallAudioTags(
            settings = settings,
            model = model,
            assistant = assistant,
            processingStatus = processingStatus,
            primaryMessages = listOf(sentenceMessage),
            format = format,
        ).firstOrNull()
        return taggedMessage?.voiceCallAudioTagAssignmentsOrEmpty()?.firstOrNull()
    }

    private suspend fun applySecondPassVoiceCallAudioTags(
        settings: Settings,
        model: Model,
        assistant: Assistant,
        processingStatus: MutableStateFlow<String?>,
        primaryMessages: List<UIMessage>,
        format: VoiceCallAudioTagFormat,
    ): List<UIMessage> {
        val primaryReplyIndex = primaryMessages.indexOfLast { message ->
            message.role == MessageRole.ASSISTANT && message.toText().isNotBlank()
        }
        if (primaryReplyIndex < 0) return primaryMessages

        val primaryReply = primaryMessages[primaryReplyIndex]
        val primaryReplyText = primaryReply.toText().trim()
        val originalSegments = splitVoiceCallAudioTaggingSegments(primaryReplyText)
        val taggingSegmentIndexes = selectVoiceCallAudioTaggingSegmentIndexes(
            segments = originalSegments,
            englishOnly = settings.displaySetting.ttsEnglishOnly,
        )
        if (taggingSegmentIndexes.isEmpty()) return primaryMessages
        val taggingSegments = taggingSegmentIndexes.map(originalSegments::get)
        val filteredTaggingSegmentIndexes = taggingSegmentIndexes
            .takeIf { settings.displaySetting.ttsEnglishOnly }
        val taggingContext = listOf(
            UIMessage.user(
                buildVoiceCallAudioTaggingRequest(taggingSegments)
            )
        )
        val taggingAssistant = assistant.copy(
            systemPrompt = "",
            temperature = 0f,
            topP = 1f,
            contextMessageSize = 1,
            streamOutput = false,
            enableMemory = false,
            useGlobalMemory = false,
            enableRecentChatsReference = false,
            reasoningLevel = ReasoningLevel.OFF,
            localTools = emptyList(),
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            enabledSkills = emptySet(),
            enableTimeReminder = false,
            allowConversationSystemPrompt = false,
            allowConversationPromptInjection = false,
        )
        val voiceCallAudioTagConfig = settings.voiceCallAudioTagConfig
        val voiceCallAudioTagProviderOverride = if (voiceCallAudioTagConfig.enabled) {
            ProviderSetting.OpenAI(
                apiKey = voiceCallAudioTagConfig.apiKey,
                baseUrl = voiceCallAudioTagConfig.baseUrl,
                chatCompletionsPath = voiceCallAudioTagConfig.chatCompletionsPath,
                useResponseApi = voiceCallAudioTagConfig.useResponseApi,
            )
        } else {
            null
        }
        val voiceCallAudioTagModel = if (
            voiceCallAudioTagConfig.enabled &&
            voiceCallAudioTagConfig.modelId.isNotBlank()
        ) {
            val trimmedModelId = voiceCallAudioTagConfig.modelId.trim()
            Model(
                modelId = trimmedModelId,
                displayName = trimmedModelId,
                type = ModelType.CHAT,
            )
        } else {
            settings.voiceCallAudioTagModelId?.let(settings::findModelById) ?: model
        }
        // 二次标注只依赖本地的 select_voice_call_audio_tags 工具。模型注册表按 id 匹配能力，
        // 常见写法（Qwen3-14B / Qwen-2.5-14B-instruct）匹配不到 TOOL，各 provider 就不会把
        // tools 发出去，模型只能回文本导致 missing_tool_call 回退。这里强制带上 TOOL 能力。
        val voiceCallAudioTaggingModel = voiceCallAudioTagModel.copy(
            abilities = (voiceCallAudioTagModel.abilities + ModelAbility.TOOL).distinct()
        )
        var toolCallCount = 0
        var selectionResult: VoiceCallAudioTagSelectionResult? = null
        val tagSelectionTool = createVoiceCallAudioTagSelectionTool(
            segmentCount = taggingSegments.size,
            format = format,
            onResult = { result ->
                toolCallCount++
                selectionResult = if (toolCallCount == 1) {
                    result
                } else {
                    VoiceCallAudioTagSelectionResult.InvalidArguments
                }
            },
        )
        var requestFailureReason: VoiceCallTaggingFallbackReason? = null
        var rawTaggingResponse = ""
        Logging.log(
            TAG,
            "applySecondPassVoiceCallAudioTags: provider=${format.providerName}, " +
                "segments=${taggingSegments.size}",
        )
        try {
            generationHandler.generateText(
                settings = settings,
                model = voiceCallAudioTaggingModel,
                processingStatus = processingStatus,
                messages = taggingContext,
                assistant = taggingAssistant,
                memories = emptyList(),
                includeMemoriesInPrompt = false,
                tools = listOf(tagSelectionTool),
                maxSteps = 1,
                extraSystemPrompt = buildVoiceCallAudioTagPrompt(format),
                maxTokensOverride = 2048,
                providerOverride = voiceCallAudioTagProviderOverride,
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) {
                    rawTaggingResponse = chunk.messages.lastOrNull()?.toText().orEmpty()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            requestFailureReason = VoiceCallTaggingFallbackReason.REQUEST_ERROR
            Logging.log(TAG, "applySecondPassVoiceCallAudioTags: $error")
        }
        if (requestFailureReason == null) {
            requestFailureReason = when (selectionResult) {
                null -> VoiceCallTaggingFallbackReason.MISSING_TOOL_CALL
                VoiceCallAudioTagSelectionResult.InvalidArguments ->
                    VoiceCallTaggingFallbackReason.INVALID_TOOL_ARGUMENTS

                is VoiceCallAudioTagSelectionResult.Selected -> null
            }
        }
        Logging.log(
            TAG,
            "applySecondPassVoiceCallAudioTags: completed toolCalls=$toolCallCount, " +
                "selection=${selectionResult?.javaClass?.simpleName ?: "missing"}, " +
                "failure=${requestFailureReason?.displayName ?: "none"}",
        )

        val selectedAssignments = (selectionResult as? VoiceCallAudioTagSelectionResult.Selected)?.assignments
        // 部分模型不调用工具、直接回 JSON 文本：客户端只取校验过的 tagId，按索引映射回自己的
        // 原文分段来渲染，绝不使用模型回填的正文，避免模型改写文本进入语音副本。
        val parsedTextFallbackAssignments = if (selectedAssignments == null && rawTaggingResponse.isNotBlank()) {
            parseVoiceCallAudioTagResponse(rawTaggingResponse, format)
                .takeIf { parsed ->
                    parsed.segments.isNotEmpty() &&
                        parsed.segments.all { it.selectionSource != VoiceCallTagSelectionSource.FALLBACK }
                }
                ?.segments
                ?.map { segment ->
                    VoiceCallAudioTagAssignment(
                        tagId = segment.tag?.id,
                        replacementText = segment.replacementText,
                    )
                }
                ?.takeIf { it.size == taggingSegments.size }
        } else {
            null
        }
        return primaryMessages.mapIndexed { index, message ->
            if (index == primaryReplyIndex) {
                when {
                    selectedAssignments != null -> message.withSelectedVoiceCallAudioTagAssignments(
                        selectedAssignments = selectedAssignments,
                        taggingSegmentIndexes = filteredTaggingSegmentIndexes,
                        format = format,
                        selectionFailureReason = requestFailureReason,
                    )

                    parsedTextFallbackAssignments != null -> message.withSelectedVoiceCallAudioTagAssignments(
                        selectedAssignments = parsedTextFallbackAssignments,
                        taggingSegmentIndexes = filteredTaggingSegmentIndexes,
                        format = format,
                        selectionFailureReason = null,
                    )

                    else -> message.withSelectedVoiceCallAudioTagAssignments(
                        selectedAssignments = null,
                        taggingSegmentIndexes = filteredTaggingSegmentIndexes,
                        format = format,
                        selectionFailureReason = requestFailureReason,
                    )
                }
            } else {
                message
            }
        }
    }

    // ---- 检查无效消息 ----

    private suspend fun buildMomentContextPromptIfNeeded(
        assistantId: Uuid,
        messages: List<UIMessage>,
    ): String? {
        val latestUserText = messages
            .asReversed()
            .firstOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.asMomentPlainText()
            .orEmpty()
        if (!latestUserText.shouldInjectMomentContext()) {
            return null
        }

        val timeline = momentRepository.getTimeline(assistantId).take(6)
        if (timeline.isEmpty()) {
            return """
                ## Moments context
                The user appears to be asking about Moments, but there are no saved Moments for this assistant yet.
                Say this naturally if relevant. Do not invent Moments content.
            """.trimIndent()
        }

        return buildString {
            appendLine("## Moments context")
            appendLine("The user appears to be referring to Moments / social-feed content.")
            appendLine("Use the saved Moments below as lightweight context for this reply. Do not claim there are other Moments, likes, comments, or images beyond this context.")
            appendLine()
            timeline.forEachIndexed { index, entry ->
                appendMomentEntry(index + 1, entry)
            }
        }.trim()
    }

    private suspend fun buildAnonymousQuestionContextPromptIfNeeded(
        scopeId: Uuid,
        messages: List<UIMessage>,
    ): String? {
        val latestUserText = messages
            .asReversed()
            .firstOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.asMomentPlainText()
            .orEmpty()
        if (!latestUserText.shouldInjectAnonymousQuestionContext()) return null

        val entries = anonymousQuestionRepository.getEntries(scopeId).take(6)
        if (entries.isEmpty()) {
            return """
                ## Anonymous question-box context
                The user is referring to the anonymous question box, but it has no saved questions yet.
                Do not invent anonymous questions or replies.
            """.trimIndent()
        }
        return buildString {
            appendLine("## Anonymous question-box context")
            appendLine("Use the saved anonymous questions below only when relevant. Never infer or reveal who wrote them.")
            appendLine()
            entries.forEachIndexed { index, entry ->
                appendAnonymousQuestionEntry(index + 1, entry)
            }
        }.trim()
    }

    private fun String.shouldInjectAnonymousQuestionContext(): Boolean {
        val text = lowercase(Locale.ROOT)
        val strongSignals = listOf("匿名提问箱", "提问箱", "匿名问题", "匿名提问", "匿名箱", "anonymous question")
        if (strongSignals.any { it in text }) return true
        val references = listOf("那条问题", "这个问题", "刚才的问题", "上面的问题", "你问的问题", "回答问题")
        return references.any { it in text }
    }

    private fun StringBuilder.appendAnonymousQuestionEntry(index: Int, entry: AnonymousQuestionEntry) {
        appendLine("$index. Question: ${entry.question.content.take(500)}")
        entry.replies.takeLast(4).forEach { reply ->
            appendLine("Reply: ${reply.content.take(300)}")
        }
        appendLine()
    }

    private fun String.shouldInjectMomentContext(): Boolean {
        val text = lowercase(Locale.ROOT)
        val strongSignals = listOf(
            "朋友圈",
            "朋友 圈",
            "moments",
            "moment",
            "说说",
            "空间动态",
            "动态圈",
            "朋友圈内容",
        )
        if (strongSignals.any { it in text }) return true

        if ("动态规划" in text) return false
        val socialSubjects = listOf(
            "你发",
            "你刚发",
            "你刚才发",
            "我发",
            "我刚发",
            "我刚才发",
            "那条",
            "这条",
            "上条",
            "上一条",
            "刚才那条",
            "下面",
        )
        val socialActions = listOf(
            "动态",
            "点赞",
            "赞了",
            "评论",
            "留言",
            "回复",
            "红点",
            "封面",
            "背景图",
        )
        return socialSubjects.any { it in text } && socialActions.any { it in text }
    }

    private fun StringBuilder.appendMomentEntry(index: Int, entry: MomentEntry) {
        val moment = entry.moment
        val author = when (moment.author) {
            MomentAuthor.USER -> "USER"
            MomentAuthor.ASSISTANT -> "ASSISTANT"
        }
        appendLine("$index. [$author] ${moment.createdAt.toMomentTimeLabel()}")
        if (moment.content.isNotBlank()) {
            appendLine("Content: ${moment.content.take(500)}")
        }
        if (moment.contextNote.isNotBlank()) {
            appendLine("Hidden context note: ${moment.contextNote.take(300)}")
        }
        if (moment.imageUris.isNotEmpty()) {
            appendLine("Images: ${moment.imageUris.size}")
        }
        if (moment.imageDescription.isNotBlank()) {
            appendLine("Image description: ${moment.imageDescription.take(400)}")
        }
        if (moment.aiLiked) {
            appendLine("Assistant liked this user Moment.")
        }
        if (moment.aiReplyContent.isNotBlank()) {
            appendLine("Assistant reaction: ${moment.aiReplyContent.take(300)}")
        }
        if (moment.userLiked) {
            appendLine("User liked this assistant Moment.")
        }
        entry.comments.takeLast(4).forEach { comment ->
            val commentAuthor = when (comment.author) {
                MomentAuthor.USER -> "USER"
                MomentAuthor.ASSISTANT -> "ASSISTANT"
            }
            appendLine("Comment [$commentAuthor] ${comment.createdAt.toMomentTimeLabel()}: ${comment.content.take(260)}")
        }
        appendLine()
    }

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() })
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() }),
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32,
        autoCompressConfig: AutoCompressConfig? = null,
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        initializeConversation(conversationId)
        val compressionBase = getConversationFlow(conversationId).value.normalizeCompressionState()
        val expectedSummary = compressionBase.compressedSummary
        val expectedCompressedNodeIds = compressionBase.activeCompressedMessageNodeIds
        compressionDiagnostics.record(
            conversationId = conversationId,
            stage = "compress.start",
            conversation = compressionBase,
            details = "targetTokens=$targetTokens keepRecent=$keepRecentMessages",
        )
        val model = settings.compressModelId?.let(settings::findModelById)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val compressionProvider = provider.withCompressionApiOverride(settings.compressOpenAIConfig)
        val compressionModel = model.withCompressionModelOverride(settings.compressOpenAIConfig)

        val providerHandler = providerManager.getProviderByType(compressionProvider)

        val visibleNodes = compressionBase.visibleMessageNodes
        val standaloneVoiceCallRecordNodes = visibleNodes
            .filter { it.currentMessage.isStandaloneVoiceCallRecord() }
        val voiceCallMessageIds = standaloneVoiceCallRecordNodes
            .flatMapTo(mutableSetOf()) { node ->
                node.currentMessage.voiceCallRecord()?.messageIds.orEmpty()
            }
        val allNodes = visibleNodes.filterNot { it.currentMessage.isStandaloneVoiceCallRecord() }
        val allMessages = allNodes.map { it.currentMessage }
        if (allMessages.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }
        val effectiveKeepRecentMessages = effectiveCompressionKeepRecentMessages(keepRecentMessages)

        // Split messages into those to compress and those to keep
        val nodesToCompress: List<MessageNode>
        val messagesToCompress: List<UIMessage>

        if (allMessages.size > effectiveKeepRecentMessages) {
            nodesToCompress = allNodes.dropLast(effectiveKeepRecentMessages)
            messagesToCompress = nodesToCompress.map { node ->
                node.currentMessage.forConversationCompression(
                    isVoiceCallTranscript = node.currentMessage.id.toString() in voiceCallMessageIds,
                )
            }
        } else {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        suspend fun generateCompressedSummary(contentToCompress: String, extraContext: String): String {
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to extraContext,
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = compressionProvider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = compressionModel,
                ),
            )

            return result.choices[0].message?.toText()?.trim()
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        suspend fun mergeSummaries(summaries: List<String>, extraContext: String): String {
            val nonBlankSummaries = summaries.map { it.trim() }.filter { it.isNotBlank() }
            if (nonBlankSummaries.size <= 1) return nonBlankSummaries.singleOrNull().orEmpty()

            val mergedSummaries = splitTextsForCompression(nonBlankSummaries, targetTokens)
                .map { chunk ->
                    val contentToMerge = chunk.mapIndexed { index, summary ->
                        "Partial summary ${index + 1}:\n$summary"
                    }.joinToString("\n\n")
                    generateCompressedSummary(
                        contentToCompress = contentToMerge,
                        extraContext = extraContext
                    )
                }

            return if (mergedSummaries.size == nonBlankSummaries.size) {
                mergedSummaries.joinToString("\n\n")
            } else {
                mergeSummaries(mergedSummaries, extraContext)
            }
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val extraContext = buildString {
                append("Summarize only the new messages below.")
                if (additionalPrompt.isNotBlank()) {
                    appendLine()
                    append("Additional instructions from user: $additionalPrompt")
                }
            }
            return generateCompressedSummary(contentToCompress, extraContext)
        }

        // 压缩请求体大、耗时长，限制并发，避免网关同时掐断多个连接。
        val compressionSemaphore = Semaphore(2)
        val compressedSummaries = coroutineScope {
            splitMessagesForCompression(messagesToCompress, targetTokens)
                .map { chunk -> async { compressionSemaphore.withPermit { compressMessages(chunk) } } }
                .awaitAll()
        }

        val compressedSummary = mergeSummaries(
            summaries = compressedSummaries,
            extraContext = buildString {
                append("Merge these partial summaries into one coherent current conversation summary. ")
                append("Remove duplicate headings and duplicate facts, preserve important decisions, ")
                append("user preferences, constraints, open tasks, and current state.")
                if (additionalPrompt.isNotBlank()) {
                    appendLine()
                    append("Additional instructions from user: $additionalPrompt")
                }
            }
        )
        val previousSummary = expectedSummary?.takeIf { it.isNotBlank() }
        val summaryForPrompt = if (previousSummary == null) {
            compressedSummary.ifBlank { null }
        } else {
            val rollingSummaryInput = buildString {
                appendLine("Existing rolling summary:")
                appendLine(previousSummary)
                appendLine()
                appendLine("New summary to merge:")
                appendLine(compressedSummary)
            }
            generateCompressedSummary(
                contentToCompress = rollingSummaryInput,
                extraContext = buildString {
                    append("Merge the existing rolling summary and the new summary into one current conversation summary. ")
                    append("Remove duplicate facts, preserve important decisions, user preferences, constraints, open tasks, and current state. ")
                    append("Mark superseded or corrected information as outdated only when it matters.")
                    if (additionalPrompt.isNotBlank()) {
                        appendLine()
                        append("Additional instructions from user: $additionalPrompt")
                    }
                }
            ).ifBlank { null }
        }
        val selectedNodeIds = nodesToCompress.mapTo(mutableSetOf()) { it.id }
        val coveredNodeIds = expectedCompressedNodeIds + selectedNodeIds
        val voiceCallRecordNodeIdsToCompress =
            compressionBase.voiceCallRecordNodeIdsFullyCoveredBy(coveredNodeIds)
        val nodeIdsToCompress = selectedNodeIds + voiceCallRecordNodeIdsToCompress
        val latestConversation = getConversationFlow(conversationId).value
        val newConversation = latestConversation
            .withCompressionResultIfBaseUnchanged(
                expectedSummary = expectedSummary,
                expectedCompressedNodeIds = expectedCompressedNodeIds,
                newSummary = summaryForPrompt,
                nodeIdsToCompress = nodeIdsToCompress,
                newAutoCompressConfig = autoCompressConfig?.copy(
                    keepRecentMessages = effectiveKeepRecentMessages
                ),
            )
            ?.copy(chatSuggestions = emptyList())
        if (newConversation == null) {
            compressionDiagnostics.record(
                conversationId = conversationId,
                stage = "compress.rejected",
                conversation = latestConversation,
                details = "expectedSummaryChars=${expectedSummary?.length ?: 0} expectedCompressed=${expectedCompressedNodeIds.size}",
            )
            return@runCatching
        }

        compressionDiagnostics.record(
            conversationId = conversationId,
            stage = "compress.accepted",
            conversation = newConversation,
            details = "newlyCompressed=${nodeIdsToCompress.size}",
        )
        saveConversation(conversationId, newConversation)
        compressionDiagnostics.record(
            conversationId = conversationId,
            stage = "compress.saved",
            conversation = getConversationFlow(conversationId).value,
        )
    }

    private suspend fun autoCompressConversationIfNeeded(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val config = if (assistant.allowConversationSystemPrompt) {
            conversation.autoCompressConfig
        } else {
            assistant.autoCompressConfig
        } ?: return
        if (!config.enabled) return

        val triggerMessageCount = assistant.contextMessageSize
        if (triggerMessageCount <= 0) return

        val keepRecentMessages = config.keepRecentMessages.coerceAtLeast(1)
        if (conversation.visibleMessageNodes.size < keepRecentMessages + triggerMessageCount) return

        compressConversation(
            conversationId = conversationId,
            additionalPrompt = config.additionalPrompt,
            targetTokens = config.targetTokens,
            keepRecentMessages = keepRecentMessages,
            autoCompressConfig = if (assistant.allowConversationSystemPrompt) {
                config.copy(keepRecentMessages = keepRecentMessages)
            } else {
                null
            },
        ).onFailure {
            // 自动压缩是后台优化：失败时只记录日志、跳过本次压缩，不弹错误卡片打断聊天。
            Logging.log(TAG, "autoCompressConversationIfNeeded: $it")
        }
    }

    private fun ProviderSetting.withCompressionApiOverride(
        config: CompressOpenAIConfig,
    ): ProviderSetting {
        if (!config.enabled) return this
        return ProviderSetting.OpenAI(
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            chatCompletionsPath = config.chatCompletionsPath,
            useResponseApi = config.useResponseApi,
        )
    }

    private fun me.rerere.ai.provider.Model.withCompressionModelOverride(
        config: CompressOpenAIConfig,
    ): me.rerere.ai.provider.Model {
        if (!config.enabled || config.modelId.isBlank()) return this
        return copy(modelId = config.modelId.trim())
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.removePrefix("mcp__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(
        conversationId: Uuid,
        conversation: Conversation,
        checkFiles: Boolean = true,
    ) {
        if (conversation.id != conversationId) return
        val normalizedConversation = conversation.normalizeCompressionState()
        if (normalizedConversation.compressedMessageNodeIds != conversation.compressedMessageNodeIds) {
            compressionDiagnostics.record(
                conversationId = conversationId,
                stage = "normalize.changed",
                conversation = normalizedConversation,
                details = "beforeStored=" + conversation.compressedMessageNodeIds.size +
                    " beforeActive=" + conversation.activeCompressedMessageNodeIds.size,
            )
        }
        val session = getOrCreateSession(conversationId)
        if (checkFiles) {
            checkFilesDelete(normalizedConversation, session.state.value)
        }
        session.replaceState(normalizedConversation)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        if (newConversation.messageNodes === oldConversation.messageNodes) return
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.normalizeCompressionState()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val loadingText = context.getString(R.string.translating)
                val currentMessage = getConversationFlow(conversationId).value.messageNodes
                    .asSequence()
                    .flatMap { it.messages.asSequence() }
                    .firstOrNull { it.id == message.id }

                // A saved translation is the cache for this message. The clear action is
                // the explicit opt-in to translate it again.
                if (currentMessage?.translation?.let {
                        it.isNotBlank() && it != loadingText
                    } == true
                ) {
                    return@launch
                }

                val sourceMessage = currentMessage ?: message
                val messageText = sourceMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    fun translateVoiceCallBubble(
        conversationId: Uuid,
        message: UIMessage,
        bubbleKey: String,
        sourceText: String,
        targetLanguage: Locale,
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val loadingText = context.getString(R.string.translating)
                val currentMessage = getConversationFlow(conversationId).value.messageNodes
                    .asSequence()
                    .flatMap { it.messages.asSequence() }
                    .firstOrNull { it.id == message.id }
                val cachedTranslation = currentMessage?.voiceCallTranslations?.get(bubbleKey)
                if (cachedTranslation?.isNotBlank() == true && cachedTranslation != loadingText) {
                    return@launch
                }
                if (sourceText.isBlank()) return@launch

                updateVoiceCallTranslationField(conversationId, message.id, bubbleKey, loadingText)
                generationHandler.translateText(
                    settings = settings,
                    sourceText = sourceText,
                    targetLanguage = targetLanguage,
                ) { translatedText ->
                    updateVoiceCallTranslationField(
                        conversationId = conversationId,
                        messageId = message.id,
                        bubbleKey = bubbleKey,
                        translationText = translatedText.sanitizeVoiceCallTextForTranslation(),
                    )
                }.collect { }

                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                updateVoiceCallTranslationField(
                    conversationId = conversationId,
                    messageId = message.id,
                    bubbleKey = bubbleKey,
                    translationText = null,
                )
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    fun translateChatVoiceSegment(
        conversationId: Uuid,
        message: UIMessage,
        segmentIndex: Int,
        sourceText: String,
        targetLanguage: Locale,
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()
                val loadingText = context.getString(R.string.translating)
                val currentMessage = getConversationFlow(conversationId).value.messageNodes
                    .asSequence()
                    .flatMap { it.messages.asSequence() }
                    .firstOrNull { it.id == message.id }
                val cachedTranslation = currentMessage
                    ?.chatVoiceReply()
                    ?.segments
                    ?.getOrNull(segmentIndex)
                    ?.translation
                if (cachedTranslation?.isNotBlank() == true && cachedTranslation != loadingText) {
                    return@launch
                }
                if (sourceText.isBlank()) return@launch

                updateChatVoiceSegmentTranslation(
                    conversationId = conversationId,
                    messageId = message.id,
                    segmentIndex = segmentIndex,
                    translationText = loadingText,
                )
                generationHandler.translateText(
                    settings = settings,
                    sourceText = sourceText,
                    targetLanguage = targetLanguage,
                ) { translatedText ->
                    updateChatVoiceSegmentTranslation(
                        conversationId = conversationId,
                        messageId = message.id,
                        segmentIndex = segmentIndex,
                        translationText = translatedText,
                    )
                }.collect { }
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                updateChatVoiceSegmentTranslation(
                    conversationId = conversationId,
                    messageId = message.id,
                    segmentIndex = segmentIndex,
                    translationText = null,
                )
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    private fun updateVoiceCallTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        bubbleKey: String,
        translationText: String?,
        clearLegacyTranslation: Boolean = false,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                node.copy(
                    messages = node.messages.map { msg ->
                        if (msg.id == messageId) {
                            val translations = msg.voiceCallTranslations.toMutableMap().apply {
                                if (translationText.isNullOrBlank()) {
                                    remove(bubbleKey)
                                } else {
                                    put(bubbleKey, translationText)
                                }
                            }
                            msg.copy(
                                translation = if (clearLegacyTranslation) null else msg.translation,
                                voiceCallTranslations = translations,
                            )
                        } else {
                            msg
                        }
                    }
                )
            } else {
                node
            }
        }
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    private fun updateChatVoiceSegmentTranslation(
        conversationId: Uuid,
        messageId: Uuid,
        segmentIndex: Int,
        translationText: String?,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.none { it.id == messageId }) {
                node
            } else {
                node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == messageId) {
                            message.updateChatVoiceReplySegment(segmentIndex) { segment ->
                                segment.copy(translation = translationText?.takeIf { it.isNotBlank() })
                            }
                        } else {
                            message
                        }
                    }
                )
            }
        }
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }
        compressionDiagnostics.record(
            conversationId = conversationId,
            stage = "fork.before",
            conversation = currentConversation,
            details = "targetIndex=" + targetNodeIndex,
        )

        val copiedNodePairs = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { sourceNode ->
                sourceNode.id to sourceNode.copy(
                    id = Uuid.random(),
                    messages = sourceNode.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }
        val copiedNodeIdsBySourceId = copiedNodePairs.associate { (sourceNodeId, copiedNode) ->
            sourceNodeId to copiedNode.id
        }
        val copiedNodes = copiedNodePairs.map { it.second }
        val targetNodeId = currentConversation.messageNodes[targetNodeIndex].id
        val forkCompressionState = currentConversation.compressionStateForFork(
            targetNodeId = targetNodeId,
            copiedNodeIdsBySourceId = copiedNodeIdsBySourceId,
        )

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
            compressedSummary = forkCompressionState.summary,
            compressedMessageNodeIds = forkCompressionState.compressedNodeIds,
            autoCompressConfig = currentConversation.autoCompressConfig,
        )

        saveConversation(forkConversation.id, forkConversation)
        compressionDiagnostics.record(
            conversationId = conversationId,
            stage = "fork.completed",
            conversation = currentConversation,
            details = "forkId=" + forkConversation.id +
                " copiedNodes=" + copiedNodes.size +
                " mappedCompressed=" + forkCompressionState.compressedNodeIds.size,
        )
        compressionDiagnostics.record(
            conversationId = forkConversation.id,
            stage = "fork.created",
            conversation = forkConversation,
            details = "sourceId=" + conversationId + " targetIndex=" + targetNodeIndex,
        )
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteVoiceCallRecord(
        conversationId: Uuid,
        callId: String,
    ) {
        val conversation = getConversationFlow(conversationId).value
        val recordMessages = conversation.messageNodes
            .flatMap { it.messages }
            .mapNotNull { message ->
                message.voiceCallRecord()
                    ?.takeIf { it.callId == callId }
                    ?.let { record -> message to record }
            }
        if (recordMessages.isEmpty()) return

        val linkedMessageIds = buildSet {
            recordMessages.forEach { (message, record) ->
                addAll(record.messageIds)
                if (!record.standalone) add(message.id.toString())
            }
        }
        val audioUris = recordMessages
            .flatMap { (_, record) ->
                record.audioSegments + record.audioSegmentsByMessageId.values.flatten()
            }
            .map { it.audioUri }
            .distinct()
            .map(String::toUri)

        val updatedNodes = conversation.messageNodes.mapNotNull { node ->
            val remainingMessages = node.messages.filterNot { message ->
                message.id.toString() in linkedMessageIds ||
                    message.voiceCallRecord()?.callId == callId
            }
            if (remainingMessages.isEmpty()) {
                null
            } else {
                node.copy(
                    messages = remainingMessages,
                    selectIndex = node.selectIndex.coerceAtMost(remainingMessages.lastIndex),
                )
            }
        }
        saveConversation(
            conversationId,
            conversation.copy(messageNodes = updatedNodes),
        )
        filesManager.deleteChatFiles(audioUris)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        appScope.launch(Dispatchers.IO) {
            val currentConversation = getConversationFlow(conversationId).value
            val updatedNodes = currentConversation.messageNodes.map { node ->
                if (node.messages.any { it.id == messageId }) {
                    val updatedMessages = node.messages.map { msg ->
                        if (msg.id == messageId) {
                            msg.copy(translation = null)
                        } else {
                            msg
                        }
                    }
                    node.copy(messages = updatedMessages)
                } else {
                    node
                }
            }

            saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
        }
    }

    fun clearVoiceCallTranslation(
        conversationId: Uuid,
        messageId: Uuid,
        bubbleKey: String,
        clearLegacyTranslation: Boolean,
    ) {
        appScope.launch(Dispatchers.IO) {
            updateVoiceCallTranslationField(
                conversationId = conversationId,
                messageId = messageId,
                bubbleKey = bubbleKey,
                translationText = null,
                clearLegacyTranslation = clearLegacyTranslation,
            )
            saveConversation(conversationId, getConversationFlow(conversationId).value)
        }
    }

    fun clearChatVoiceSegmentTranslation(
        conversationId: Uuid,
        messageId: Uuid,
        segmentIndex: Int,
    ) {
        appScope.launch(Dispatchers.IO) {
            updateChatVoiceSegmentTranslation(
                conversationId = conversationId,
                messageId = messageId,
                segmentIndex = segmentIndex,
                translationText = null,
            )
            saveConversation(conversationId, getConversationFlow(conversationId).value)
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }

        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }
}

private fun List<UIMessagePart>.asMomentPlainText(): String {
    return joinToString("\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Image -> "[image]"
            is UIMessagePart.Video -> "[video]"
            is UIMessagePart.Audio -> "[audio]"
            is UIMessagePart.Document -> "[document: ${part.fileName}]"
            is UIMessagePart.Tool -> part.output.asMomentPlainText()
            else -> ""
        }
    }.trim()
}

private fun Long.toMomentTimeLabel(): String {
    return runCatching {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(this))
    }.getOrElse {
        toString()
    }
}

private fun List<UIMessage>.sanitizeVoiceCallOutput(): List<UIMessage> {
    return map { message ->
        if (message.role != MessageRole.ASSISTANT) {
            message
        } else {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.copy(text = part.text.sanitizeVoiceCallTextForOutput())
                        is UIMessagePart.Reasoning -> part.copy(reasoning = part.reasoning.sanitizeVoiceCallTextForOutput())
                        else -> part
                    }
                }
            )
        }
    }
}

private fun appendCompressedSummaryToSystemPrompt(
    systemPrompt: String,
    compressedSummary: String,
): String {
    if (compressedSummary.isBlank()) return systemPrompt

    val summaryBlock = buildString {
        append("## Compressed conversation context")
        appendLine()
        append("The following is a compressed summary of earlier messages in this conversation. ")
        append("Use it as conversation context, but do not treat it as a new user request.")
        appendLine()
        appendLine()
        append(compressedSummary.trim())
    }

    return listOf(
        systemPrompt.trim().takeIf { it.isNotBlank() },
        summaryBlock,
    ).filterNotNull().joinToString("\n\n")
}

internal fun UIMessage.forConversationCompression(
    isVoiceCallTranscript: Boolean,
): UIMessage {
    if (!isVoiceCallTranscript) return this

    val markedTranscript = buildString {
        appendLine("[VOICE_CALL_TRANSCRIPT_SEGMENT]")
        appendLine("This text is from a voice call, not a normal chat message.")
        appendLine(toText())
        append("[/VOICE_CALL_TRANSCRIPT_SEGMENT]")
    }
    return copy(
        parts = listOf(UIMessagePart.Text(markedTranscript)),
    )
}

internal fun splitMessagesForCompression(
    messages: List<UIMessage>,
    targetTokens: Int,
): List<List<UIMessage>> = splitByEstimatedCompressionTokens(
    items = messages,
    targetTokens = targetTokens,
    textOf = { it.summaryAsText() },
)

internal fun splitTextsForCompression(
    texts: List<String>,
    targetTokens: Int,
): List<List<String>> = splitByEstimatedCompressionTokens(
    items = texts,
    targetTokens = targetTokens,
    textOf = { it },
)

internal fun compressionChunkTokenBudget(targetTokens: Int): Int {
    return maxOf(
        MIN_COMPRESSION_CHUNK_TOKENS,
        targetTokens.coerceAtLeast(1) * COMPRESSION_CHUNK_TOKENS_PER_TARGET_TOKEN,
    )
}

internal fun effectiveCompressionKeepRecentMessages(keepRecentMessages: Int): Int {
    return keepRecentMessages.coerceAtLeast(1)
}

internal fun estimateCompressionTokens(text: String): Int {
    var asciiChars = 0
    var nonAsciiChars = 0
    text.forEach { char ->
        if (char.code <= 0x7F) {
            asciiChars++
        } else {
            nonAsciiChars++
        }
    }
    return ((asciiChars + 3) / 4 + nonAsciiChars).coerceAtLeast(1)
}

private fun <T> splitByEstimatedCompressionTokens(
    items: List<T>,
    targetTokens: Int,
    textOf: (T) -> String,
): List<List<T>> {
    if (items.isEmpty()) return emptyList()

    val tokenBudget = compressionChunkTokenBudget(targetTokens)
    val chunks = mutableListOf<List<T>>()
    var currentChunk = mutableListOf<T>()
    var currentTokens = 0

    items.forEach { item ->
        val itemTokens = estimateCompressionTokens(textOf(item))
        if (currentChunk.isNotEmpty() && currentTokens + itemTokens > tokenBudget) {
            chunks += currentChunk
            currentChunk = mutableListOf()
            currentTokens = 0
        }
        currentChunk += item
        currentTokens += itemTokens
    }

    if (currentChunk.isNotEmpty()) {
        chunks += currentChunk
    }
    return chunks
}
