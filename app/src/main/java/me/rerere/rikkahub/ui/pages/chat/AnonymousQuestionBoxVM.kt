package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.AnonymousQuestion
import me.rerere.rikkahub.data.repository.AnonymousQuestionAuthor
import me.rerere.rikkahub.data.repository.AnonymousQuestionEntry
import me.rerere.rikkahub.data.repository.AnonymousQuestionReply
import me.rerere.rikkahub.data.repository.AnonymousQuestionReplyStatus
import me.rerere.rikkahub.data.repository.AnonymousQuestionRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

internal const val ANONYMOUS_RESPONSE_MAX_TOKENS = 512

internal fun Assistant.forAnonymousQuestionGeneration(): Assistant {
    return copy(streamOutput = false, reasoningLevel = ReasoningLevel.OFF)
}

internal fun List<UIMessagePart>.anonymousQuestionVisibleText(): String {
    return filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
}

class AnonymousQuestionBoxVM(
    private val settingsStore: SettingsStore,
    private val repository: AnonymousQuestionRepository,
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
) : ViewModel() {
    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()
    private val processingIds = mutableSetOf<String>()

    fun observeQuestions(scopeId: Uuid): Flow<List<AnonymousQuestionEntry>> = repository.observeQuestions(scopeId)
    fun observeProfile(scopeId: Uuid) = repository.observeProfile(scopeId)
    fun observeHasUnread(scopeId: Uuid) = repository.observeHasUnread(scopeId)

    fun markViewed(scopeId: Uuid) {
        viewModelScope.launch { repository.markViewed(scopeId) }
    }

    fun postUserQuestion(scopeId: Uuid, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch { repository.postUserQuestion(scopeId, content) }
    }

    fun addUserAnswer(question: AnonymousQuestion, content: String) {
        if (content.isBlank() || question.author != AnonymousQuestionAuthor.ASSISTANT) return
        viewModelScope.launch { repository.addUserAnswer(question.id, content) }
    }

    fun processDue(
        scopeId: Uuid,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ) {
        viewModelScope.launch {
            if (_processing.value) return@launch
            _processing.value = true
            try {
                val generationConversation = conversationRepository.getConversationById(conversation.id) ?: conversation
                val now = System.currentTimeMillis()
                repository.getDueQuestions(scopeId, now, AnonymousQuestionRepository.MAX_PROCESSING_ITEMS)
                    .forEach { processQuestion(it, assistant, generationConversation, conversationSystemPrompt) }
                repository.getDueAnswers(scopeId, now, AnonymousQuestionRepository.MAX_PROCESSING_ITEMS)
                    .forEach { processAnswer(it, assistant, generationConversation, conversationSystemPrompt) }
            } finally {
                _processing.value = false
            }
        }
    }

    private suspend fun processQuestion(
        question: AnonymousQuestion,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ) {
        val key = "question:${question.id}"
        if (!processingIds.add(key)) return
        try {
            val answer = generateAnonymousAnswer(question.content, assistant, conversation, conversationSystemPrompt) ?: return
            repository.addAssistantAnswer(question.id, answer)
            repository.updateQuestion(question.copy(replyStatus = AnonymousQuestionReplyStatus.DONE))
        } finally {
            processingIds.remove(key)
        }
    }

    private suspend fun processAnswer(
        reply: AnonymousQuestionReply,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ) {
        val key = "answer:${reply.id}"
        if (!processingIds.add(key)) return
        try {
            val question = repository.getQuestion(reply.questionId) ?: return
            val comment = generateAnonymousComment(question.content, reply.content, assistant, conversation, conversationSystemPrompt) ?: return
            repository.addAssistantComment(question.id, comment)
            repository.updateReply(reply.copy(replyStatus = AnonymousQuestionReplyStatus.DONE))
        } finally {
            processingIds.remove(key)
        }
    }

    private suspend fun generateAnonymousAnswer(
        question: String,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ): String? {
        return generateText(
            assistant = assistant,
            conversationSystemPrompt = conversationSystemPrompt,
            conversationContextSummary = conversation.compressedSummary,
            messages = listOf(UIMessage.user(
            prompt = """
                Answer an anonymous question in the assistant's anonymous question box.
                You do not know who asked it. Do not infer, identify, name, or expose the asker.
                Return only a natural, concise answer with no markdown and no identity claims.
                Keep the complete answer within 200 characters and finish it naturally before reaching the limit.

                Anonymous question:
                $question
            """.trimIndent(),
            )),
            maxTokens = ANONYMOUS_RESPONSE_MAX_TOKENS,
        )
    }

    private suspend fun generateAnonymousComment(
        question: String,
        answer: String,
        assistant: Assistant,
        conversation: Conversation,
        conversationSystemPrompt: String?,
    ): String? {
        return generateText(
            assistant = assistant,
            conversationSystemPrompt = conversationSystemPrompt,
            conversationContextSummary = conversation.compressedSummary,
            messages = listOf(UIMessage.user(
            prompt = """
                Write a short natural comment on an anonymous question-box answer.
                The question and answer are anonymous. Do not infer who wrote either one.
                Do not reveal that the assistant authored the question or mention any identity.
                Return only the comment text, with no markdown.
                Keep the complete comment within 200 characters and finish it naturally before reaching the limit.

                Anonymous question:
                $question

                Anonymous answer:
                $answer
            """.trimIndent(),
            )),
            maxTokens = ANONYMOUS_RESPONSE_MAX_TOKENS,
        )
    }

    private suspend fun generateText(
        assistant: Assistant,
        conversationSystemPrompt: String?,
        conversationContextSummary: String?,
        messages: List<UIMessage>,
        maxTokens: Int,
    ): String? {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return null
        val memories = if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        }
        var output = ""
        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = messages,
            // The 200-character rule is expressed in the prompt; this budget is a provider token limit.
            assistant = assistant.forAnonymousQuestionGeneration(),
            conversationSystemPrompt = conversationSystemPrompt,
            conversationContextSummary = conversationContextSummary,
            memories = memories,
            includeMemoriesInPrompt = true,
            tools = emptyList(),
            maxSteps = 1,
            maxTokensOverride = maxTokens,
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) {
                output = chunk.messages.lastOrNull()?.parts?.anonymousQuestionVisibleText().orEmpty()
            }
        }
        return output.takeIf { it.isNotBlank() }
    }
}
