package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AnonymousQuestionEntity
import me.rerere.rikkahub.data.db.entity.AnonymousQuestionProfileEntity
import me.rerere.rikkahub.data.db.entity.AnonymousQuestionReplyEntity

@Dao
interface AnonymousQuestionDAO {
    @Query("SELECT * FROM anonymous_questions WHERE scope_id = :scopeId ORDER BY created_at DESC")
    fun observeQuestions(scopeId: String): Flow<List<AnonymousQuestionEntity>>

    @Query("SELECT * FROM anonymous_questions WHERE scope_id = :scopeId ORDER BY created_at DESC")
    suspend fun getQuestions(scopeId: String): List<AnonymousQuestionEntity>

    @Query(
        "SELECT r.* FROM anonymous_question_replies r INNER JOIN anonymous_questions q ON q.id = r.question_id WHERE q.scope_id = :scopeId ORDER BY r.created_at ASC"
    )
    fun observeReplies(scopeId: String): Flow<List<AnonymousQuestionReplyEntity>>

    @Query(
        "SELECT r.* FROM anonymous_question_replies r INNER JOIN anonymous_questions q ON q.id = r.question_id WHERE q.scope_id = :scopeId ORDER BY r.created_at ASC"
    )
    suspend fun getRepliesForScope(scopeId: String): List<AnonymousQuestionReplyEntity>

    @Query("SELECT * FROM anonymous_questions WHERE id = :id")
    suspend fun getQuestion(id: String): AnonymousQuestionEntity?

    @Query("DELETE FROM anonymous_questions WHERE id = :id")
    suspend fun deleteQuestion(id: String)

    @Query("SELECT * FROM anonymous_question_replies WHERE question_id = :questionId ORDER BY created_at ASC")
    suspend fun getReplies(questionId: String): List<AnonymousQuestionReplyEntity>

    @Query("SELECT * FROM anonymous_question_replies WHERE id = :id")
    suspend fun getReply(id: String): AnonymousQuestionReplyEntity?

    @Query(
        """
        SELECT * FROM anonymous_questions
        WHERE scope_id = :scopeId AND author = :author
        AND reply_status = :replyStatus AND reply_due_at <= :now
        ORDER BY reply_due_at ASC LIMIT :limit
        """
    )
    suspend fun getDueQuestions(scopeId: String, author: String, replyStatus: String, now: Long, limit: Int): List<AnonymousQuestionEntity>

    @Query(
        """
        SELECT r.* FROM anonymous_question_replies r
        INNER JOIN anonymous_questions q ON q.id = r.question_id
        WHERE q.scope_id = :scopeId AND r.author = :author
        AND r.reply_status = :replyStatus AND r.reply_due_at <= :now
        ORDER BY r.reply_due_at ASC LIMIT :limit
        """
    )
    suspend fun getDueReplies(scopeId: String, author: String, replyStatus: String, now: Long, limit: Int): List<AnonymousQuestionReplyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuestion(question: AnonymousQuestionEntity)

    @Update
    suspend fun updateQuestion(question: AnonymousQuestionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReply(reply: AnonymousQuestionReplyEntity)

    @Update
    suspend fun updateReply(reply: AnonymousQuestionReplyEntity)

    @Transaction
    suspend fun insertUserAnswerIfAbsent(reply: AnonymousQuestionReplyEntity): Boolean {
        val question = getQuestion(reply.questionId) ?: return false
        if (question.author != "assistant" || getReplies(question.id).any {
                it.author == "user" && it.kind == "answer"
            }) return false
        upsertReply(reply)
        return true
    }

    @Transaction
    suspend fun completeQuestionAnswer(reply: AnonymousQuestionReplyEntity): Boolean {
        val current = getQuestion(reply.questionId) ?: return false
        if (current.author != "user" || current.replyStatus != "pending") return false
        upsertReply(reply)
        updateQuestion(current.copy(replyStatus = "done"))
        return true
    }

    @Transaction
    suspend fun completeAnswerComment(answerId: String, reply: AnonymousQuestionReplyEntity): Boolean {
        val current = getReply(answerId) ?: return false
        if (current.author != "user" || current.kind != "answer" ||
            current.replyStatus != "pending" || current.questionId != reply.questionId
        ) return false
        upsertReply(reply)
        updateReply(current.copy(replyStatus = "done"))
        return true
    }

    @Query("SELECT * FROM anonymous_question_profiles WHERE scope_id = :scopeId")
    fun observeProfile(scopeId: String): Flow<AnonymousQuestionProfileEntity?>

    @Query("SELECT * FROM anonymous_question_profiles WHERE scope_id = :scopeId")
    suspend fun getProfile(scopeId: String): AnonymousQuestionProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: AnonymousQuestionProfileEntity)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM anonymous_questions
            WHERE scope_id = :scopeId AND author = :assistantAuthor AND created_at > :lastViewedAt
        ) OR EXISTS(
            SELECT 1 FROM anonymous_question_replies r
            INNER JOIN anonymous_questions q ON q.id = r.question_id
            WHERE q.scope_id = :scopeId AND r.author = :assistantAuthor AND r.created_at > :lastViewedAt
        )
        """
    )
    fun observeHasUnread(scopeId: String, lastViewedAt: Long, assistantAuthor: String): Flow<Boolean>
}
