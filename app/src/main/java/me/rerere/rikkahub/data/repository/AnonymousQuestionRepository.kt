package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.AnonymousQuestionDAO
import me.rerere.rikkahub.data.db.entity.AnonymousQuestionEntity
import me.rerere.rikkahub.data.db.entity.AnonymousQuestionProfileEntity
import me.rerere.rikkahub.data.db.entity.AnonymousQuestionReplyEntity
import kotlin.random.Random
import kotlin.uuid.Uuid

class AnonymousQuestionRepository(private val dao: AnonymousQuestionDAO) {
    fun observeQuestions(scopeId: Uuid): Flow<List<AnonymousQuestionEntry>> {
        val key = scopeId.toString()
        return combine(dao.observeQuestions(key), dao.observeReplies(key)) { questions, replies ->
            val repliesByQuestion = replies.groupBy { it.questionId }
            questions.map { question ->
                AnonymousQuestionEntry(
                    question = question.toQuestion(),
                    replies = repliesByQuestion[question.id].orEmpty().map { it.toReply() },
                )
            }
        }
    }

    fun observeProfile(scopeId: Uuid): Flow<AnonymousQuestionProfile> =
        dao.observeProfile(scopeId.toString()).map { it?.toProfile() ?: AnonymousQuestionProfile(scopeId) }

    fun observeHasUnread(scopeId: Uuid): Flow<Boolean> =
        dao.observeProfile(scopeId.toString()).flatMapLatest { profile ->
            dao.observeHasUnread(scopeId.toString(), profile?.lastViewedAt ?: 0L, AnonymousQuestionAuthor.ASSISTANT.value)
        }

    suspend fun getQuestion(id: Uuid): AnonymousQuestion? = dao.getQuestion(id.toString())?.toQuestion()

    suspend fun deleteQuestions(
        scopeId: Uuid,
        questionId: Uuid?,
        keyword: String,
        latest: Boolean,
        limit: Int,
    ): List<AnonymousQuestion> {
        val scopeKey = scopeId.toString()
        val candidates: List<AnonymousQuestion> = when {
            questionId != null -> listOfNotNull(dao.getQuestion(questionId.toString()))
                .filter { it.scopeId == scopeKey }
                .map { it.toQuestion() }

            keyword.isNotBlank() -> getEntries(scopeId)
                .filter { entry ->
                    entry.question.content.contains(keyword, ignoreCase = true) ||
                        entry.replies.any { it.content.contains(keyword, ignoreCase = true) }
                }
                .map { it.question }

            latest -> dao.getQuestions(scopeKey).take(1).map { it.toQuestion() }
            else -> emptyList()
        }.take(limit.coerceIn(1, 20))

        candidates.forEach { dao.deleteQuestion(it.id.toString()) }
        return candidates
    }

    suspend fun getEntries(scopeId: Uuid): List<AnonymousQuestionEntry> {
        val questions = dao.getQuestions(scopeId.toString())
        val replies = dao.getRepliesForScope(scopeId.toString()).groupBy { it.questionId }
        return questions.map { question ->
            AnonymousQuestionEntry(
                question = question.toQuestion(),
                replies = replies[question.id].orEmpty().map { it.toReply() },
            )
        }
    }

    suspend fun getReplies(questionId: Uuid): List<AnonymousQuestionReply> =
        dao.getReplies(questionId.toString()).map { it.toReply() }

    suspend fun postUserQuestion(scopeId: Uuid, content: String, now: Long = System.currentTimeMillis()): Uuid {
        val id = Uuid.random()
        dao.upsertQuestion(
            AnonymousQuestionEntity(
                id = id.toString(), scopeId = scopeId.toString(), author = AnonymousQuestionAuthor.USER.value,
                content = content.trim(),
                replyDueAt = now + Random.nextLong(
                    USER_QUESTION_REPLY_DELAY_MINUTES.first,
                    USER_QUESTION_REPLY_DELAY_MINUTES.last + 1,
                ) * MILLIS_PER_MINUTE,
                replyStatus = AnonymousQuestionReplyStatus.PENDING.value, createdAt = now,
            )
        )
        return id
    }

    suspend fun postAssistantQuestion(scopeId: Uuid, content: String, now: Long = System.currentTimeMillis()): Uuid {
        val id = Uuid.random()
        dao.upsertQuestion(
            AnonymousQuestionEntity(
                id = id.toString(), scopeId = scopeId.toString(), author = AnonymousQuestionAuthor.ASSISTANT.value,
                content = content.trim(), replyDueAt = null,
                replyStatus = AnonymousQuestionReplyStatus.NONE.value, createdAt = now,
            )
        )
        return id
    }

    suspend fun addUserAnswer(questionId: Uuid, content: String, now: Long = System.currentTimeMillis()): Uuid? {
        val id = Uuid.random()
        val inserted = dao.insertUserAnswerIfAbsent(
            AnonymousQuestionReplyEntity(
                id = id.toString(), questionId = questionId.toString(), author = AnonymousQuestionAuthor.USER.value,
                kind = AnonymousQuestionReplyKind.ANSWER.value, content = content.trim(),
                replyDueAt = now + Random.nextLong(
                    USER_ANSWER_COMMENT_DELAY_MINUTES.first,
                    USER_ANSWER_COMMENT_DELAY_MINUTES.last + 1,
                ) * MILLIS_PER_MINUTE,
                replyStatus = AnonymousQuestionReplyStatus.PENDING.value, createdAt = now,
            )
        )
        return id.takeIf { inserted }
    }

    suspend fun completeQuestionAnswer(questionId: Uuid, content: String, now: Long = System.currentTimeMillis()): Boolean =
        dao.completeQuestionAnswer(assistantReply(questionId, AnonymousQuestionReplyKind.ANSWER, content, now))

    suspend fun completeAnswerComment(answer: AnonymousQuestionReply, content: String, now: Long = System.currentTimeMillis()): Boolean =
        dao.completeAnswerComment(answer.id.toString(), assistantReply(answer.questionId, AnonymousQuestionReplyKind.COMMENT, content, now))

    private fun assistantReply(questionId: Uuid, kind: AnonymousQuestionReplyKind, content: String, now: Long) =
            AnonymousQuestionReplyEntity(
                id = Uuid.random().toString(), questionId = questionId.toString(), author = AnonymousQuestionAuthor.ASSISTANT.value,
                kind = kind.value, content = content.trim(), replyDueAt = null,
                replyStatus = AnonymousQuestionReplyStatus.DONE.value, createdAt = now,
            )

    suspend fun getDueQuestions(scopeId: Uuid, now: Long, limit: Int): List<AnonymousQuestion> =
        dao.getDueQuestions(scopeId.toString(), AnonymousQuestionAuthor.USER.value, AnonymousQuestionReplyStatus.PENDING.value, now, limit)
            .map { it.toQuestion() }

    suspend fun getDueAnswers(scopeId: Uuid, now: Long, limit: Int): List<AnonymousQuestionReply> =
        dao.getDueReplies(scopeId.toString(), AnonymousQuestionAuthor.USER.value, AnonymousQuestionReplyStatus.PENDING.value, now, limit)
            .filter { it.kind == AnonymousQuestionReplyKind.ANSWER.value }
            .map { it.toReply() }

    suspend fun markViewed(scopeId: Uuid, viewedAt: Long = System.currentTimeMillis()) {
        dao.upsertProfile(AnonymousQuestionProfileEntity(scopeId.toString(), viewedAt))
    }

    companion object {
        const val MAX_PROCESSING_ITEMS = 3
        private const val MILLIS_PER_MINUTE = 60_000L
        private val USER_QUESTION_REPLY_DELAY_MINUTES = 10L..20L
        private val USER_ANSWER_COMMENT_DELAY_MINUTES = 3L..8L
    }
}

data class AnonymousQuestionEntry(val question: AnonymousQuestion, val replies: List<AnonymousQuestionReply>)

data class AnonymousQuestion(
    val id: Uuid, val scopeId: Uuid, val author: AnonymousQuestionAuthor, val content: String,
    val replyDueAt: Long?, val replyStatus: AnonymousQuestionReplyStatus, val createdAt: Long,
)

data class AnonymousQuestionReply(
    val id: Uuid, val questionId: Uuid, val author: AnonymousQuestionAuthor, val kind: AnonymousQuestionReplyKind,
    val content: String, val replyDueAt: Long?, val replyStatus: AnonymousQuestionReplyStatus, val createdAt: Long,
)

data class AnonymousQuestionProfile(val scopeId: Uuid, val lastViewedAt: Long = 0L)

enum class AnonymousQuestionAuthor(val value: String) { USER("user"), ASSISTANT("assistant") }
enum class AnonymousQuestionReplyKind(val value: String) { ANSWER("answer"), COMMENT("comment") }
enum class AnonymousQuestionReplyStatus(val value: String) { NONE("none"), PENDING("pending"), DONE("done") }

private fun AnonymousQuestionEntity.toQuestion() = AnonymousQuestion(
    id = Uuid.parse(id), scopeId = Uuid.parse(scopeId), author = AnonymousQuestionAuthor.entries.first { it.value == author },
    content = content, replyDueAt = replyDueAt,
    replyStatus = AnonymousQuestionReplyStatus.entries.first { it.value == replyStatus }, createdAt = createdAt,
)

private fun AnonymousQuestionReplyEntity.toReply() = AnonymousQuestionReply(
    id = Uuid.parse(id), questionId = Uuid.parse(questionId), author = AnonymousQuestionAuthor.entries.first { it.value == author },
    kind = AnonymousQuestionReplyKind.entries.first { it.value == kind }, content = content, replyDueAt = replyDueAt,
    replyStatus = AnonymousQuestionReplyStatus.entries.first { it.value == replyStatus }, createdAt = createdAt,
)

private fun AnonymousQuestionProfileEntity.toProfile() = AnonymousQuestionProfile(Uuid.parse(scopeId), lastViewedAt)
