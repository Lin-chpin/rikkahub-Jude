package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryScope
import me.rerere.rikkahub.data.model.messagesForGeneration
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import kotlin.uuid.Uuid

class HeartbeatGenerationWorkflow(
    private val context: Context,
) : KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val generationHandler: GenerationHandler by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val localTools: LocalTools by inject()
    private val mcpManager: McpManager by inject()
    private val chatService: ChatService by inject()
    private val json: Json by inject()
    private val deliveryGuard by lazy { HeartbeatDeliveryGuard(context, chatService) }
    private val privateExperienceStore = HeartbeatPrivateExperienceStore(context)
    suspend fun run(
        config: HeartbeatConfig,
        mode: HeartbeatExecutionMode = HeartbeatExecutionMode.LIVE,
    ): HeartbeatGenerationResult {
        val runStartedAtMillis = System.currentTimeMillis()
        val isLiveRun = mode == HeartbeatExecutionMode.LIVE

        val settings = settingsStore.settingsFlow.first()
        val scheduleStore = HeartbeatScheduleStore(context)
        val autonomousPlan = scheduleStore.readForAssistant(config.assistantId)
            ?.takeIf { isLiveRun }
            ?.takeIf { plan -> plan.wakeAtMillis.minOrNull()?.let { it <= runStartedAtMillis } == true }
        val assistant = settings.assistants
            .firstOrNull { it.id.toString() == autonomousPlan?.assistantId }
            ?: settings.assistants.firstOrNull { it.id.toString() == config.assistantId }
            ?: settings.getCurrentAssistant()
        if (isLiveRun && autonomousPlan != null) {
            scheduleStore.consumeDue(
                assistantId = config.assistantId,
                nowMillis = runStartedAtMillis,
            )
        }
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.NO_MODEL,
                reason = HeartbeatRunReason.NO_MODEL,
            )

        val storedConversation = conversationRepository
            .getRecentConversations(assistant.id, limit = 1)
            .firstOrNull()
            ?.let { conversationRepository.getConversationById(it.id) }
        val conversationId = storedConversation?.id ?: Uuid.random()

        deliveryGuard.beforeGeneration(
            conversationId = conversationId,
            expectedAssistantId = assistant.id.toString(),
        )?.let { block ->
            recordExperience(mode, conversationId, block.name)
            return block.toGenerationResult()
        }
        val completedConversationMessages = storedConversation
            ?.currentMessages
            .orEmpty()
            .filterCompletedToolMessages()
        val generationConversationMessages = storedConversation
            ?.messagesForGeneration()
            .orEmpty()
            .filterCompletedToolMessages()
        val history = generationConversationMessages
            .let { messages ->
                if (assistant.contextMessageSize > 0) {
                    messages.takeLast(assistant.contextMessageSize)
                } else {
                    messages
                }
            }
        Logging.log(
            tag = "Heartbeat",
            message = "context=all:${completedConversationMessages.size} generation:${generationConversationMessages.size} " +
                "history:${history.size} compressed:${storedConversation?.activeCompressedMessageNodeIds?.size ?: 0} " +
                "summary:${!storedConversation?.compressedSummary.isNullOrBlank()}",
        )
        if (history.lastOrNull()?.role == MessageRole.USER) {
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.PENDING_USER,
                reason = HeartbeatRunReason.USER_REPLY_PENDING,
            )
        }

        val goodNightActive = if (isLiveRun) {
            updateGoodNightMode(completedConversationMessages, config.assistantId)
        } else {
            readGoodNightMode(config.assistantId)
        }

        if (isLiveRun) updateLastUserMessageAt(completedConversationMessages, config.assistantId)
        val desireState = currentDesireState(
            runStartedAtMillis,
            persist = isLiveRun,
            assistantId = config.assistantId,
        )

        val prompt = UIMessage.user(
            HeartbeatPromptContext.build(config.heartbeatPrompt, completedConversationMessages),
        )
        val requestMessages = history + prompt
        val allAvailableTools = buildAvailableTools(settings, assistant, conversationId)
        val allowedTools = HeartbeatToolPolicy(config).filter(allAvailableTools, mode)
        // Heartbeat is bound to the conversation it is about; assistant/global scopes
        // would reintroduce cross-window memory leakage during background runs.
        val conversationMemoryScope = MemoryScope.conversation(conversationId)
        val memories = if (assistant.enableMemory) {
            memoryRepository.getMemories(conversationMemoryScope)
        } else {
            emptyList()
        }
        val generationAssistant = assistant.copy(
            enableMemory = false,
            enableRecentChatsReference = false,
            streamOutput = false,
        )
        var generatedMessages: List<UIMessage> = requestMessages

        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = requestMessages,
            assistant = generationAssistant,
            conversationId = conversationId,
            memories = memories,
            includeMemoriesInPrompt = assistant.enableMemory,
            tools = allowedTools,
            maxSteps = config.maxToolSteps,
            conversationSystemPrompt = storedConversation?.customSystemPrompt,
            conversationContextSummary = storedConversation?.compressedSummary,
            conversationModeInjectionIds = storedConversation?.modeInjectionIds.orEmpty(),
            conversationLorebookIds = storedConversation?.lorebookIds.orEmpty(),
            extraSystemPrompt = if (goodNightActive) GOOD_NIGHT_SYSTEM_PROMPT else HEARTBEAT_SYSTEM_PROMPT,
            inputTransformers = listOf(
                TimeReminderTransformer,
                PromptInjectionTransformer,
                PlaceholderTransformer,
                DocumentAsPromptTransformer,
                OcrTransformer,
                templateTransformer,
            ),
            outputTransformers = buildList {
                add(ThinkTagTransformer)
                // Base64 extraction writes local files, so it is excluded from diagnostic runs.
                if (isLiveRun) add(Base64ImageToLocalFileTransformer)
                add(RegexOutputTransformer)
            },
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) generatedMessages = chunk.messages
        }

        deliveryGuard.beforeDelivery(
            conversationId = conversationId,
            runStartedAtMillis = runStartedAtMillis,
            expectedAssistantId = assistant.id.toString(),
        )?.let { block ->
            recordExperience(mode, conversationId, block.name, desireState)
            return block.toGenerationResult()
        }

        if (isLiveRun && goodNightActive) {
            updateGoodNightNoUsageCounter(generatedMessages, config.assistantId)
        }

        val rawGeneratedText = generatedMessages
            .drop(requestMessages.size)
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.toText()
            .orEmpty()
        val generatedText = rawGeneratedText
            .replace(PASS_MARKER, "")
            .trim()
        if (PASS_MARKER.containsMatchIn(rawGeneratedText) || generatedText.isBlank()) {
            recordExperience(mode, conversationId, "PASS", desireState)
            return HeartbeatGenerationResult(
                outcome = if (isLiveRun) {
                    HeartbeatGenerationOutcome.PASS
                } else {
                    HeartbeatGenerationOutcome.TESTED
                },
                reason = HeartbeatRunReason.MODEL_DECIDED_PASS,
            )
        }

        val decision = HeartbeatDecisionEngine(
            desireState = desireState,
            sentTexts = HeartbeatPrivateExperienceStore(context, config.assistantId)
                .recentDeliveredTexts(),
        ).evaluate(generatedText)
        if (!decision.shouldDeliver) {
            recordExperience(
                mode = mode,
                conversationId = conversationId,
                outcome = "SKIPPED_DECISION",
                state = desireState,
                decision = decision,
                text = generatedText,
            )
            return HeartbeatGenerationResult(
                outcome = if (isLiveRun) {
                    HeartbeatGenerationOutcome.PASS
                } else {
                    HeartbeatGenerationOutcome.TESTED
                },
                reason = HeartbeatRunReason.NOVELTY_FILTERED,
                detail = "score=${formatScore(decision.score)} novelty=${formatScore(decision.novelty)}",
            )
        }

        if (!isLiveRun) {
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.TESTED,
                reason = HeartbeatRunReason.READ_ONLY_WOULD_SEND,
                detail = generatedText.take(120),
            )
        }

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(generatedText)),
        )
        val savedConversationId = appendMessage(
            conversationId = conversationId,
            assistant = assistant,
            storedConversation = storedConversation,
            message = message,
            runStartedAtMillis = runStartedAtMillis,
        ) ?: run {
            recordExperience(
                mode = mode,
                conversationId = conversationId,
                outcome = "DELIVERY_BLOCKED",
                state = desireState,
                decision = decision,
            )
            return HeartbeatGenerationResult(
                outcome = HeartbeatGenerationOutcome.BUSY,
                reason = HeartbeatRunReason.CONVERSATION_BUSY,
            )
        }

        HeartbeatUserActivity.recordAssistantMessage(
            context = context,
            message = message,
            assistantId = config.assistantId,
        )

        recordExperience(
            mode = mode,
            conversationId = conversationId,
            outcome = HeartbeatPrivateExperienceStore.OUTCOME_SENT,
            state = desireState,
            decision = decision,
            text = generatedText,
        )
        updateDesireAfterDelivery(config.assistantId)

        HeartbeatNotifications.showMessage(
            context = context,
            conversationId = savedConversationId.toString(),
            senderName = assistant.name.ifBlank { model.displayName },
            message = generatedText,
        )
        return HeartbeatGenerationResult(
            outcome = HeartbeatGenerationOutcome.SENT,
            reason = HeartbeatRunReason.MESSAGE_SENT,
        )
    }

    private fun currentDesireState(
        nowMillis: Long,
        persist: Boolean,
        assistantId: String?,
    ): HeartbeatDesireState {
        val store = HeartbeatConfigStore(context, assistantId)
        return try {
            store.readDesireState().advance(nowMillis).also { state ->
                if (persist) store.recordDesireState(state)
            }
        } finally {
            store.close()
        }
    }

    private fun updateDesireAfterDelivery(assistantId: String?) {
        val nowMillis = System.currentTimeMillis()
        val store = HeartbeatConfigStore(context, assistantId)
        try {
            store.recordDesireState(store.readDesireState().afterDelivery(nowMillis))
        } finally {
            store.close()
        }
    }

    private fun recordExperience(
        mode: HeartbeatExecutionMode,
        conversationId: Uuid?,
        outcome: String,
        state: HeartbeatDesireState? = null,
        decision: HeartbeatThoughtDecision? = null,
        text: String? = null,
    ) {
        if (mode != HeartbeatExecutionMode.LIVE) return
        privateExperienceStore.append(
            HeartbeatPrivateExperience(
                createdAtMillis = System.currentTimeMillis(),
                conversationId = conversationId?.toString(),
                outcome = outcome,
                pressure = decision?.pressure ?: state?.pressure(),
                score = decision?.score,
                text = text?.take(1_000),
            ),
        )
    }

    private fun readGoodNightMode(assistantId: String?): Boolean {
        val store = HeartbeatConfigStore(context, assistantId)
        return try {
            store.isGoodNightActive()
        } finally {
            store.close()
        }
    }

    private fun formatScore(value: Double): String = "%.2f".format(java.util.Locale.US, value)


    private fun updateGoodNightMode(messages: List<UIMessage>, assistantId: String?): Boolean {
        val store = HeartbeatConfigStore(context, assistantId)
        try {
            val lastUserText = messages
                .lastOrNull { it.role == MessageRole.USER }
                ?.toText()
                ?.trim()
            val wasActive = store.isGoodNightActive()
            val active = when {
                lastUserText?.contains("晚安") == true -> {
                    store.setGoodNightActive(true)
                    store.setGoodNightNoUsageRuns(0)
                    true
                }
                wasActive && lastUserText != null -> {
                    // 用户已重新发言且不是晚安：视为醒来，退出晚安模式
                    store.setGoodNightActive(false)
                    store.setGoodNightNoUsageRuns(0)
                    false
                }
                else -> wasActive
            }
            Logging.log(
                tag = "Heartbeat",
                message = "goodnight=active:$active wasActive:$wasActive",
            )
            return active
        } finally {
            store.close()
        }
    }

    private fun updateLastUserMessageAt(messages: List<UIMessage>, assistantId: String?) {
        val lastUserAtMillis = messages
            .lastOrNull { it.role == MessageRole.USER }
            ?.createdAt
            ?.toInstant(TimeZone.currentSystemDefault())
            ?.toEpochMilliseconds()
        if (lastUserAtMillis != null) {
            val store = HeartbeatConfigStore(context, assistantId)
            try {
                store.setLastUserMessageAt(lastUserAtMillis)
            } finally {
                store.close()
            }
        }
    }
    private fun updateGoodNightNoUsageCounter(
        generatedMessages: List<UIMessage>,
        assistantId: String?,
    ) {
        val lockedSomething = generatedMessages.any { message ->
            message.parts.any { part ->
                part is UIMessagePart.Tool &&
                    part.toolName == "usage_lock_control" &&
                    part.isExecuted &&
                    part.inputAsJson().jsonObject["action"]?.jsonPrimitive?.contentOrNull == "lock"
            }
        }
        val store = HeartbeatConfigStore(context, assistantId)
        try {
            if (lockedSomething) {
                store.setGoodNightNoUsageRuns(0)
                Logging.log(tag = "Heartbeat", message = "goodnight=usage-found")
                return
            }
            val runs = store.goodNightNoUsageRuns() + 1
            if (runs >= GOOD_NIGHT_MAX_NO_USAGE_RUNS) {
                store.setGoodNightActive(false)
                store.setGoodNightNoUsageRuns(0)
                Logging.log(tag = "Heartbeat", message = "goodnight=closed no-usage-runs=$runs")
            } else {
                store.setGoodNightNoUsageRuns(runs)
                Logging.log(tag = "Heartbeat", message = "goodnight=no-usage runs=$runs")
            }
        } finally {
            store.close()
        }
    }
    private fun buildAvailableTools(
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
    ): List<Tool> = buildList {
        val conversationMemoryScope = MemoryScope.conversation(conversationId)
        addAll(
            localTools.getTools(
                options = (assistant.localTools + LocalToolOption.VoiceCall).distinct(),
                usageLockEnabled = true,
                voiceCallConfigured = settings.getSelectedTTSProvider() != null,
                momentAssistantId = assistant.id,
                anonymousQuestionScopeId = assistant.id,
                includeBuildTools = false,
            ).map { tool ->
                if (tool.name == "ask_user") {
                    tool.copy(
                        needsApproval = false,
                        execute = { arguments ->
                            val questions = arguments.jsonObject["questions"]
                                ?.jsonArray
                                .orEmpty()
                                .mapNotNull { question ->
                                    question.jsonObject["question"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        ?.trim()
                                        ?.takeIf(String::isNotEmpty)
                                }
                            val questionText = questions.joinToString("\n")
                                .ifBlank { "The assistant has a question for you." }
                            HeartbeatNotifications.showQuestion(
                                context = context,
                                conversationId = conversationId.toString(),
                                senderName = assistant.name.ifBlank { "AI" },
                                question = questionText,
                            )
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("delivered", true)
                                        put("delivery", "notification")
                                        put("question", questionText)
                                        put("instruction", "Repeat the question in the final assistant message.")
                                    }.toString(),
                                ),
                            )
                        },
                    )
                } else {
                    tool
                }
            },
        )
        addAll(
            buildMemoryTools(
                json = json,
                onCreation = { memoryRepository.addMemory(conversationMemoryScope, it) },
                onUpdate = { id, content ->
                    memoryRepository.updateMemory(conversationMemoryScope, id, content)
                },
                onDelete = { memoryRepository.deleteMemory(conversationMemoryScope, it) },
            ),
        )
        mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
            add(
                Tool(
                    name = "mcp__" + tool.name,
                    description = tool.description.orEmpty(),
                    parameters = { tool.inputSchema },
                    needsApproval = tool.needsApproval,
                    execute = { arguments ->
                        mcpManager.callTool(serverId, tool.name, arguments.jsonObject)
                    },
                ),
            )
        }
    }

    private suspend fun appendMessage(
        conversationId: Uuid,
        assistant: Assistant,
        storedConversation: Conversation?,
        message: UIMessage,
        runStartedAtMillis: Long,
    ): Uuid? = conversationWriteMutex.withLock {
        if (deliveryGuard.beforeDelivery(
                conversationId = conversationId,
                runStartedAtMillis = runStartedAtMillis,
                expectedAssistantId = assistant.id.toString(),
            ) != null
        ) {
            return@withLock null
        }

        val latest = conversationRepository.getConversationById(conversationId)
            ?: storedConversation
            ?: Conversation(
                id = conversationId,
                assistantId = assistant.id,
                title = assistant.name.ifBlank { "AI" },
                messageNodes = emptyList(),
            )
        val updated = latest.copy(
            messageNodes = latest.messageNodes + message.toMessageNode(),
            updateAt = Instant.now(),
        )
        if (conversationRepository.existsConversationById(conversationId)) {
            conversationRepository.updateConversation(updated)
        } else {
            conversationRepository.insertConversation(updated)
        }
        chatService.updateConversationState(conversationId) { updated }
        conversationId
    }

    private fun List<UIMessage>.filterCompletedToolMessages(): List<UIMessage> =
        filterNot { message ->
            message.parts.any { part -> part is UIMessagePart.Tool && !part.isExecuted }
        }

    companion object {
        private val conversationWriteMutex = Mutex()
        private val PASS_MARKER = Regex("\\[PASS]", RegexOption.IGNORE_CASE)
        private const val GOOD_NIGHT_MAX_NO_USAGE_RUNS = 3
        private val GOOD_NIGHT_SYSTEM_PROMPT = """
            你是当前助手的“晚安模式”定时自主唤醒。
            只能使用本请求中包含的工具（已通过后台安全白名单）。不要请求审批，也不要声称存在被屏蔽的工具。
            请检查用户使用情况，如果有新增，哪个软件新增锁哪个（有锁工具），锁到第二天白天为止。
            锁定请使用 usage_lock_control 的 action=lock，并用 unlock_at_iso 或 unlock_at_timestamp_ms 指定第二天白天的解锁时间，不要使用 duration_minutes。
            只锁定确有新增使用的软件；没有新增使用时不锁。
            输出一条简短自然的消息；若没有需要报告的内容，输出 [PASS]。
        """.trimIndent()
        private val HEARTBEAT_SYSTEM_PROMPT = """
            You are running a private, scheduled heartbeat for the current assistant.
            Use only the tools included in this request. Those tools have already passed a
            background-safety allowlist. Never ask for approval and never claim that a blocked
            tool was available. Produce one short natural message, or exactly [PASS].
        """.trimIndent()
    }
}

private fun HeartbeatDeliveryBlock.toGenerationResult(): HeartbeatGenerationResult = when (this) {
    HeartbeatDeliveryBlock.USER_RETURNED -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.PENDING_USER,
        reason = HeartbeatRunReason.USER_RETURNED,
    )
    HeartbeatDeliveryBlock.VOICE_CALL_ACTIVE -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.BUSY,
        reason = HeartbeatRunReason.VOICE_CALL_ACTIVE,
    )
    HeartbeatDeliveryBlock.CONVERSATION_BUSY -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.BUSY,
        reason = HeartbeatRunReason.CONVERSATION_BUSY,
    )
    HeartbeatDeliveryBlock.HEARTBEAT_DISABLED -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.BUSY,
        reason = HeartbeatRunReason.DISABLED,
    )
    HeartbeatDeliveryBlock.TARGET_CHANGED -> HeartbeatGenerationResult(
        outcome = HeartbeatGenerationOutcome.BUSY,
        reason = HeartbeatRunReason.TARGET_CHANGED,
    )
}
