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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.voice.VOICE_CALL_UNAVAILABLE_MESSAGE
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagFormat
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagSelectionResult
import me.rerere.rikkahub.data.voice.VoiceCallTaggingFallbackReason
import me.rerere.rikkahub.data.voice.createVoiceCallAudioTagSelectionTool
import me.rerere.rikkahub.data.voice.splitVoiceCallAudioTaggingSegments
import me.rerere.rikkahub.data.voice.voiceCallAudioTagFormatOrNull
import me.rerere.rikkahub.data.voice.withSelectedVoiceCallAudioTagIds
import me.rerere.rikkahub.data.voice.withoutVoiceCallAudioTagsForNormalContext
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AutoCompressConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.momentScopeId
import me.rerere.rikkahub.data.model.messagesForGeneration
import me.rerere.rikkahub.data.model.personaScopeId
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
private const val MIN_COMPRESSION_CHUNK_TOKENS = 8000
private const val COMPRESSION_CHUNK_TOKENS_PER_TARGET_TOKEN = 8
private const val VOICE_CALL_SYSTEM_PROMPT = """
你正在语音通话模式中回复用户。
重要状态通知：语音通话已经接通，当前正在通话中，不是在等待接听，也不是通话邀请流程。
请把接下来的用户消息直接当作当前电话中的实时发言，并在回复开头自然地确认你正在通话中。
不要再次发起、邀请、请求或切换到另一通语音通话，也不要调用打电话工具。
如果用户的表达暗示想打电话，只需在当前通话中继续回应，不要把它当成新的拨号请求。
请像真实电话聊天一样自然、简短、连贯地说话。
先用一句很短的话回应用户。
每一句都尽量短，适合一句一句朗读。
每一句都必须用句号、问号或感叹号结束。
每段最多 1 到 2 句话，一次通常控制在 2 到 4 个短段内。
不使用 Markdown 表格，不写长列表。
如果需要解释复杂问题，分成几个容易朗读的小块。
一次最多问用户一个问题。
不要使用任何表情、emoji 或颜文字。
不要输出贴纸、表情包、颜文字、ASCII 表情或类似“(≧▽≦)”的符号组合。
任何 emoji、颜文字、表情包文本都会被系统硬性删除。
"""

private val VOICE_CALL_ACTIVE_TOOL_STATUS = """
    Voice-call state: ACTIVE. The call is connected and this is the only state in which ElevenLabs v3 audio tags are allowed.
    For every subsequent spoken reply in this active call, every sentence MUST begin with exactly one English audio tag in square brackets. Every sentence must have one and only one tag; never omit a tag or let multiple sentences share one tag.
    Keep the tags in the text sent to TTS. Do not use Chinese emotion labels. Use commas, ellipses, and [pause] only when they create a natural pause.
""".trimIndent()

private val VOICE_CALL_ENDED_TOOL_STATUS = """
    Voice-call state: ENDED. The call has been disconnected. Do not generate a follow-up reply for this state and do not output any audio or emotion tags in later non-call replies.
    Audio tags may be used again only after a new call is connected and its tool result reports ACTIVE.
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
) {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

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
    ): Job = appScope.launch {
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
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        requestMode: ChatRequestMode = ChatRequestMode.Normal,
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    autoCompressConversationIfNeeded(
                        conversationId = conversationId,
                        conversation = getConversationFlow(conversationId).value
                    )
                    handleMessageComplete(conversationId, requestMode = requestMode)
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

        val job = appScope.launch {
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

        val job = appScope.launch {
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
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }

                val conversation = session.state.value
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
                        saveConversation(
                            conversationId,
                            conversation.copy(messageNodes = updatedNodes),
                        )
                    }
                }
                // Ending a call is a state update, not a new user turn. Do not
                // start a post-hangup model completion: its output would be
                // saved into the conversation and could pollute the next call's
                // context with non-voice or non-English labels.
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

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)
            val generationMessages = conversation.messagesForGeneration(messageRange).let { messages ->
                when (requestMode) {
                    ChatRequestMode.Normal ->
                        messages.map(UIMessage::withoutVoiceCallAudioTagsForNormalContext)

                    ChatRequestMode.VoiceCall -> messages
                }
            }
            val voiceCallToolEnabled = LocalToolOption.VoiceCall in assistant.localTools &&
                requestMode == ChatRequestMode.Normal
            val localToolOptions = assistant.localTools.filterNot {
                requestMode == ChatRequestMode.VoiceCall && it == LocalToolOption.VoiceCall
            }
            val voiceCallConfigured = settings.getSelectedTTSProvider() != null
            val voiceCallAudioTagFormat = settings.getSelectedTTSProvider()
                ?.voiceCallAudioTagFormatOrNull()
                ?.takeIf {
                    allowVoiceCallAudioTags && requestMode == ChatRequestMode.VoiceCall
                }
            val proactiveVoiceCallEnabled = requestMode == ChatRequestMode.Normal && voiceCallToolEnabled
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
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationContextSummary = conversation.compressedSummary,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                extraSystemPrompt = listOfNotNull(
                    when (requestMode) {
                        ChatRequestMode.Normal -> null
                        ChatRequestMode.VoiceCall -> VOICE_CALL_SYSTEM_PROMPT.trimIndent()
                    },
                    PROACTIVE_VOICE_CALL_SYSTEM_PROMPT.trimIndent().takeIf { proactiveVoiceCallEnabled },
                    momentContextPrompt,
                    ANONYMOUS_QUESTION_SYSTEM_PROMPT.takeIf {
                        requestMode == ChatRequestMode.Normal && assistant.anonymousQuestionBoxEnabled
                    },
                    anonymousQuestionContextPrompt,
                    additionalSystemPrompt,
                ).joinToString("\n\n").takeIf { it.isNotBlank() },
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
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
            generationFlow.onCompletion {
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
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
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunkMessages)
                        updateConversation(conversationId, updatedConversation)

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

            if (voiceCallAudioTagFormat != null) {
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
        val taggingSegments = splitVoiceCallAudioTaggingSegments(primaryReplyText)
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
        Logging.log(
            TAG,
            "applySecondPassVoiceCallAudioTags: provider=${format.providerName}, " +
                "segments=${taggingSegments.size}",
        )
        try {
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = processingStatus,
                messages = taggingContext,
                assistant = taggingAssistant,
                memories = emptyList(),
                includeMemoriesInPrompt = false,
                tools = listOf(tagSelectionTool),
                maxSteps = 1,
                extraSystemPrompt = buildVoiceCallAudioTagPrompt(format),
                maxTokensOverride = 2048,
            ).collect { }
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

        val selectedTagIds = (selectionResult as? VoiceCallAudioTagSelectionResult.Selected)?.tagIds
        return primaryMessages.mapIndexed { index, message ->
            if (index == primaryReplyIndex) {
                message.withSelectedVoiceCallAudioTagIds(
                    selectedTagIds = selectedTagIds,
                    format = format,
                    selectionFailureReason = requestFailureReason,
                )
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
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32,
        autoCompressConfig: AutoCompressConfig? = null,
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val compressionProvider = provider.withCompressionApiOverride(settings.compressOpenAIConfig)
        val compressionModel = model.withCompressionModelOverride(settings.compressOpenAIConfig)

        val providerHandler = providerManager.getProviderByType(compressionProvider)

        val allNodes = conversation.visibleMessageNodes
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
            messagesToCompress = nodesToCompress.map { it.currentMessage }
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

        val compressedSummaries = coroutineScope {
            splitMessagesForCompression(messagesToCompress, targetTokens)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        val latestConversation = getConversationFlow(conversationId).value
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
        val previousSummary = latestConversation.compressedSummary?.takeIf { it.isNotBlank() }
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
        val latestNodeIds = latestConversation.messageNodes.map { it.id }.toSet()
        val nodeIdsToCompress = nodesToCompress
            .map { it.id }
            .filter { it in latestNodeIds }

        val newConversation = latestConversation.copy(
            chatSuggestions = emptyList(),
            compressedSummary = summaryForPrompt,
            compressedMessageNodeIds = latestConversation.compressedMessageNodeIds + nodeIdsToCompress,
            autoCompressConfig = autoCompressConfig?.copy(
                keepRecentMessages = effectiveKeepRecentMessages
            ) ?: latestConversation.autoCompressConfig,
        )

        saveConversation(conversationId, newConversation)
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
            conversation = conversation,
            additionalPrompt = config.additionalPrompt,
            targetTokens = config.targetTokens,
            keepRecentMessages = keepRecentMessages,
            autoCompressConfig = if (assistant.allowConversationSystemPrompt) {
                config.copy(keepRecentMessages = keepRecentMessages)
            } else {
                null
            },
        ).onFailure {
            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_compress_conversation))
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

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val normalizedConversation = conversation.normalizeCompressionState()
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(normalizedConversation, session.state.value)
        session.state.value = normalizedConversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
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

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
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

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
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

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
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
