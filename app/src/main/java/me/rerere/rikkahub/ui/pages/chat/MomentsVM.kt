package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.Moment
import me.rerere.rikkahub.data.repository.MomentAuthor
import me.rerere.rikkahub.data.repository.MomentComment
import me.rerere.rikkahub.data.repository.MomentEntry
import me.rerere.rikkahub.data.repository.MomentProfile
import me.rerere.rikkahub.data.repository.MomentReplyStatus
import me.rerere.rikkahub.data.repository.MomentRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class MomentsVM(
    private val settingsStore: SettingsStore,
    private val momentRepository: MomentRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    val filesManager: FilesManager,
) : ViewModel() {
    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()
    private val _visionModelSetupRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val visionModelSetupRequired: SharedFlow<Unit> = _visionModelSetupRequired.asSharedFlow()
    private val _refreshReports = MutableSharedFlow<MomentsRefreshReport>(extraBufferCapacity = 1)
    val refreshReports: SharedFlow<MomentsRefreshReport> = _refreshReports.asSharedFlow()
    private val processingIds = mutableSetOf<String>()

    fun observeTimeline(assistantId: Uuid): Flow<List<MomentEntry>> = momentRepository.observeTimeline(assistantId)

    fun observeProfile(assistantId: Uuid): Flow<MomentProfile> = momentRepository.observeProfile(assistantId)

    fun observeHasUnread(assistantId: Uuid): Flow<Boolean> = momentRepository.observeHasUnread(assistantId)

    fun markViewed(assistantId: Uuid) {
        viewModelScope.launch {
            momentRepository.markViewed(assistantId)
        }
    }

    fun updateCover(assistantId: Uuid, coverUri: String) {
        viewModelScope.launch {
            momentRepository.updateCover(assistantId, coverUri)
        }
    }

    fun postUserMoment(assistantId: Uuid, content: String, imageUris: List<String>) {
        if (content.isBlank() && imageUris.isEmpty()) return
        viewModelScope.launch {
            momentRepository.postUserMoment(assistantId, content, imageUris)
        }
    }

    fun toggleUserLike(momentId: Uuid) {
        viewModelScope.launch {
            momentRepository.toggleUserLike(momentId)
        }
    }

    fun addComment(momentId: Uuid, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            momentRepository.addUserComment(momentId, content)
        }
    }

    fun processDue(
        assistantId: Uuid,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
        manual: Boolean = false,
    ) {
        viewModelScope.launch {
            if (_processing.value) {
                if (manual) {
                    _refreshReports.emit(MomentsRefreshReport(processingSkipped = true))
                }
                return@launch
            }
            _processing.value = true
            try {
                val now = System.currentTimeMillis()
                val moments = if (manual) {
                    momentRepository.getRefreshUserMoments(assistantId, limit = 3)
                } else {
                    momentRepository.getDueUserMoments(assistantId, now, limit = 3)
                }
                val comments = if (manual) {
                    momentRepository.getRefreshUserComments(assistantId, limit = 3)
                } else {
                    momentRepository.getDueUserComments(assistantId, now, limit = 3)
                }
                val outcomes = buildList {
                    moments.forEach { moment ->
                        add(processDueMoment(moment, assistant, conversation, conversationSystemPrompt))
                    }
                    comments.forEach { comment ->
                        add(processDueComment(comment, assistant, conversation, conversationSystemPrompt))
                    }
                }
                if (manual) {
                    _refreshReports.emit(
                        MomentsRefreshReport(
                            candidateCount = outcomes.size,
                            completedCount = outcomes.count { it.completed },
                            emptyTextCount = outcomes.count { it.issue == MomentsProcessIssue.EMPTY_TEXT },
                            invalidResponseCount = outcomes.count { it.issue == MomentsProcessIssue.INVALID_RESPONSE },
                            modelUnavailableCount = outcomes.count { it.issue == MomentsProcessIssue.MODEL_UNAVAILABLE },
                            persistenceFailureCount = outcomes.count { it.issue == MomentsProcessIssue.PERSISTENCE_FAILED },
                            generationFailureCount = outcomes.count { it.issue == MomentsProcessIssue.GENERATION_FAILED },
                            noVisibleCommentCount = outcomes.count { it.issue == MomentsProcessIssue.NO_VISIBLE_COMMENT },
                            visionModelRequiredCount = outcomes.count { it.issue == MomentsProcessIssue.VISION_MODEL_REQUIRED },
                        )
                    )
                }
            } finally {
                _processing.value = false
            }
        }
    }

    private suspend fun processDueMoment(
        moment: Moment,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ): MomentsProcessOutcome {
        val key = "moment:${moment.id}"
        if (!processingIds.add(key)) return MomentsProcessOutcome(MomentsProcessIssue.ALREADY_PROCESSING)
        try {
            val result = generateMomentReaction(moment, assistant, conversation, conversationSystemPrompt)
            val reaction = when (result) {
                MomentReactionGeneration.VisionModelSetupRequired -> {
                    _visionModelSetupRequired.emit(Unit)
                    return MomentsProcessOutcome(MomentsProcessIssue.VISION_MODEL_REQUIRED)
                }

                MomentReactionGeneration.EmptyText ->
                    return MomentsProcessOutcome(MomentsProcessIssue.EMPTY_TEXT)

                MomentReactionGeneration.InvalidResponse ->
                    return MomentsProcessOutcome(MomentsProcessIssue.INVALID_RESPONSE)

                MomentReactionGeneration.ModelUnavailable ->
                    return MomentsProcessOutcome(MomentsProcessIssue.MODEL_UNAVAILABLE)

                is MomentReactionGeneration.Ready -> result.reaction
            }
            val parsed = parseImageDescriptionOutput(reaction.comment)
            val updated = moment.copy(
                aiLiked = reaction.liked,
                aiReplyContent = parsed.visibleText,
                imageDescription = parsed.imageDescription ?: moment.imageDescription,
                repliedAt = System.currentTimeMillis(),
                replyStatus = MomentReplyStatus.DONE,
            )
            momentRepository.updateMoment(updated)
            val persisted = momentRepository.getMoment(moment.id)
            if (persisted == null ||
                persisted.replyStatus != MomentReplyStatus.DONE ||
                persisted.aiLiked != updated.aiLiked ||
                persisted.aiReplyContent != updated.aiReplyContent
            ) {
                return MomentsProcessOutcome(MomentsProcessIssue.PERSISTENCE_FAILED)
            }
            return MomentsProcessOutcome(
                issue = parsed.visibleText.takeIf { it.isBlank() }?.let {
                    MomentsProcessIssue.NO_VISIBLE_COMMENT
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return MomentsProcessOutcome(MomentsProcessIssue.GENERATION_FAILED)
        } finally {
            processingIds.remove(key)
        }
    }

    private suspend fun processDueComment(
        comment: MomentComment,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ): MomentsProcessOutcome {
        val key = "comment:${comment.id}"
        if (!processingIds.add(key)) return MomentsProcessOutcome(MomentsProcessIssue.ALREADY_PROCESSING)
        try {
            val moment = momentRepository.getMoment(comment.momentId)
                ?: return MomentsProcessOutcome(MomentsProcessIssue.PERSISTENCE_FAILED)
            val comments = momentRepository.getComments(moment.id)
            return when (val result = generateCommentReply(moment, comments, comment, assistant, conversation, conversationSystemPrompt)) {
                CommentGenerationResult.EmptyText ->
                    MomentsProcessOutcome(MomentsProcessIssue.EMPTY_TEXT)

                CommentGenerationResult.ModelUnavailable ->
                    MomentsProcessOutcome(MomentsProcessIssue.MODEL_UNAVAILABLE)

                is CommentGenerationResult.Ready -> {
                    val assistantCommentId = momentRepository.addAssistantComment(moment.id, result.text)
                    val assistantCommentPersisted = momentRepository.getComments(moment.id).any {
                        it.id == assistantCommentId &&
                            it.author == MomentAuthor.ASSISTANT &&
                            it.content == result.text
                    }
                    if (!assistantCommentPersisted) {
                        MomentsProcessOutcome(MomentsProcessIssue.PERSISTENCE_FAILED)
                    } else {
                        momentRepository.updateComment(comment.copy(replyStatus = MomentReplyStatus.DONE))
                        val userCommentPersisted = momentRepository.getComments(moment.id).any {
                            it.id == comment.id && it.replyStatus == MomentReplyStatus.DONE
                        }
                        if (!userCommentPersisted) {
                            MomentsProcessOutcome(MomentsProcessIssue.PERSISTENCE_FAILED)
                        } else {
                            MomentsProcessOutcome()
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return MomentsProcessOutcome(MomentsProcessIssue.GENERATION_FAILED)
        } finally {
            processingIds.remove(key)
        }
    }

    private suspend fun generateMomentReaction(
        moment: Moment,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ): MomentReactionGeneration {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return MomentReactionGeneration.ModelUnavailable
        val requiresOcrFallback = moment.imageUris.isNotEmpty() &&
            moment.imageDescription.isBlank() &&
            Modality.IMAGE !in model.inputModalities
        if (requiresOcrFallback && !settings.hasConfiguredVisionOcrModel()) {
            return MomentReactionGeneration.VisionModelSetupRequired
        }
        val inputTransformers = if (requiresOcrFallback) listOf(OcrTransformer) else emptyList()
        val memories = if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        }
        val timelineContext = buildTimelineContext(moment.assistantId, excludeMomentId = moment.id)
        val imageInstruction = if (moment.imageUris.isNotEmpty() && moment.imageDescription.isBlank()) {
            """
            This moment includes images. After the visible JSON response, include an [image_desc]...[/image_desc]
            block with a concise objective visual description. Do not put image description text inside JSON.comment.
            """.trimIndent()
        } else {
            ""
        }
        val prompt = """
            Generate the assistant's private reaction to this user Moment.
            Output only JSON with optional fields: {"like": true|false, "comment": "short natural comment"}.
            The comment should be brief, intimate, and specific. It may be empty.

            Recent Moments context:
            $timelineContext

            Current Moment:
            ${moment.content}
            Images: ${moment.imageUris.size}
            Stored image description: ${moment.imageDescription.ifBlank { "(none yet)" }}

            $imageInstruction
        """.trimIndent()
        val parts = buildList {
            add(UIMessagePart.Text(prompt))
            if (moment.imageDescription.isBlank()) {
                moment.imageUris.take(MomentRepository.MAX_IMAGES).forEach { uri ->
                    add(UIMessagePart.Image(uri))
                }
            }
        }
        val text = generateText(
            settings = settings,
            assistant = assistant,
            model = model,
            memories = memories,
            messages = listOf(UIMessage(
                role = MessageRole.USER,
                parts = parts,
            )),
            inputTransformers = inputTransformers,
            conversationSystemPrompt = conversationSystemPrompt,
            conversationContextSummary = conversation.compressedSummary,
            maxTokens = 360,
        )
        if (text.isBlank()) return MomentReactionGeneration.EmptyText
        val parsed = parseMomentReaction(text)
            ?: return MomentReactionGeneration.InvalidResponse
        val imageParsed = parseImageDescriptionOutput(text)
        return MomentReactionGeneration.Ready(
            parsed.copy(comment = listOf(parsed.comment, imageParsed.imageDescription?.let {
                "[image_desc]$it[/image_desc]"
            }).filterNotNull().joinToString("\n"))
        )
    }

    private suspend fun generateCommentReply(
        moment: Moment,
        comments: List<MomentComment>,
        target: MomentComment,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ): CommentGenerationResult {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return CommentGenerationResult.ModelUnavailable
        val memories = if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        }
        val prompt = """
            Reply as the assistant in a Moments comment thread.
            Keep it natural and short. Return only the reply text, no JSON and no markdown.

            Original Moment:
            ${moment.content}
            Hidden context note:
            ${moment.contextNote.ifBlank { "(none)" }}
            Image description:
            ${moment.imageDescription.ifBlank { "(none)" }}

            Recent comments:
            ${comments.takeLast(10).joinToString("\n") { "${it.author.value}: ${it.content}" }}

            Reply to this user comment:
            ${target.content}
        """.trimIndent()
        val text = generateText(
            settings = settings,
            assistant = assistant,
            model = model,
            memories = memories,
            messages = listOf(UIMessage.user(prompt)),
            conversationSystemPrompt = conversationSystemPrompt,
            conversationContextSummary = conversation.compressedSummary,
            maxTokens = 180,
        ).trim()
        return text.takeIf { it.isNotBlank() }
            ?.let(CommentGenerationResult::Ready)
            ?: CommentGenerationResult.EmptyText
    }

    private suspend fun buildTimelineContext(assistantId: Uuid, excludeMomentId: Uuid): String {
        return momentRepository.getTimeline(assistantId)
            .map { it.moment }
            .filterNot { it.id == excludeMomentId }
            .take(3)
            .joinToString("\n") { moment ->
                "${moment.author.value}: ${moment.content.take(160)}"
            }
            .ifBlank { "(none)" }
    }

    private suspend fun generateText(
        settings: Settings,
        assistant: me.rerere.rikkahub.data.model.Assistant,
        model: me.rerere.ai.provider.Model,
        memories: List<me.rerere.rikkahub.data.model.AssistantMemory>,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        conversationSystemPrompt: String?,
        conversationContextSummary: String?,
        maxTokens: Int,
    ): String {
        var output = ""
        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = messages,
            inputTransformers = inputTransformers,
            assistant = assistant.copy(streamOutput = false),
            conversationSystemPrompt = conversationSystemPrompt,
            conversationContextSummary = conversationContextSummary,
            memories = memories,
            tools = emptyList(),
            maxSteps = 1,
            maxTokensOverride = maxTokens,
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) {
                output = chunk.messages.lastOrNull()?.parts?.asPlainText().orEmpty()
            }
        }
        return output
    }

    private fun parseMomentReaction(text: String): MomentReaction? {
        val jsonText = text.extractJsonObject() ?: return null
        return runCatching {
            val obj = JsonInstant.parseToJsonElement(jsonText).jsonObject
            MomentReaction(
                liked = obj["like"]?.jsonPrimitive?.booleanOrNull
                    ?: obj["liked"]?.jsonPrimitive?.booleanOrNull
                    ?: false,
                comment = obj["comment"]?.jsonPrimitive?.contentOrNull
                    ?: obj["reply_content"]?.jsonPrimitive?.contentOrNull
                    ?: "",
            )
        }.getOrNull()
    }

    private data class MomentReaction(
        val liked: Boolean,
        val comment: String,
    )

    private sealed interface MomentReactionGeneration {
        data class Ready(val reaction: MomentReaction) : MomentReactionGeneration
        data object EmptyText : MomentReactionGeneration
        data object InvalidResponse : MomentReactionGeneration
        data object ModelUnavailable : MomentReactionGeneration
        data object VisionModelSetupRequired : MomentReactionGeneration
    }

    private sealed interface CommentGenerationResult {
        data class Ready(val text: String) : CommentGenerationResult
        data object EmptyText : CommentGenerationResult
        data object ModelUnavailable : CommentGenerationResult
    }

    private data class ParsedImageOutput(
        val visibleText: String,
        val imageDescription: String?,
    )

    private fun parseImageDescriptionOutput(text: String): ParsedImageOutput {
        val regex = Regex("\\[image_desc\\]([\\s\\S]*?)\\[/image_desc\\]", RegexOption.IGNORE_CASE)
        val imageDescription = regex.findAll(text).lastOrNull()?.groupValues?.getOrNull(1)?.trim()?.take(1000)
        val visible = text.replace(regex, "").trim()
        return ParsedImageOutput(visibleText = visible, imageDescription = imageDescription)
    }

    private fun String.extractJsonObject(): String? {
        val cleaned = replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }

    private fun List<UIMessagePart>.asPlainText(): String {
        return joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Image -> "[image: ${part.url}]"
                is UIMessagePart.Video -> "[video: ${part.url}]"
                is UIMessagePart.Audio -> "[audio]"
                is UIMessagePart.Document -> "[document: ${part.fileName}]"
                else -> ""
            }
        }.trim()
    }
}

data class MomentsRefreshReport(
    val candidateCount: Int = 0,
    val completedCount: Int = 0,
    val emptyTextCount: Int = 0,
    val invalidResponseCount: Int = 0,
    val modelUnavailableCount: Int = 0,
    val persistenceFailureCount: Int = 0,
    val generationFailureCount: Int = 0,
    val noVisibleCommentCount: Int = 0,
    val visionModelRequiredCount: Int = 0,
    val processingSkipped: Boolean = false,
)

private enum class MomentsProcessIssue {
    EMPTY_TEXT,
    INVALID_RESPONSE,
    MODEL_UNAVAILABLE,
    PERSISTENCE_FAILED,
    GENERATION_FAILED,
    NO_VISIBLE_COMMENT,
    VISION_MODEL_REQUIRED,
    ALREADY_PROCESSING,
}

private data class MomentsProcessOutcome(
    val issue: MomentsProcessIssue? = null,
) {
    val completed: Boolean
        get() = issue == null || issue == MomentsProcessIssue.NO_VISIBLE_COMMENT
}

private fun Settings.hasConfiguredVisionOcrModel(): Boolean {
    val configuredModelSupportsImages = findModelById(ocrModelId)
        ?.inputModalities
        ?.contains(Modality.IMAGE) == true
    val customOcrModelConfigured = ocrOpenAIConfig.enabled && ocrOpenAIConfig.modelId.isNotBlank()
    return configuredModelSupportsImages || customOcrModelConfigured
}
