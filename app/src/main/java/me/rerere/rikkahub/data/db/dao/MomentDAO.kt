package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MomentCommentEntity
import me.rerere.rikkahub.data.db.entity.MomentEntity
import me.rerere.rikkahub.data.db.entity.MomentProfileEntity

@Dao
interface MomentDAO {
    @Query("SELECT * FROM moments WHERE assistant_id = :assistantId ORDER BY created_at DESC")
    fun observeMoments(assistantId: String): Flow<List<MomentEntity>>

    @Query("SELECT * FROM moments WHERE assistant_id = :assistantId ORDER BY created_at DESC")
    suspend fun getMoments(assistantId: String): List<MomentEntity>

    @Query("SELECT * FROM moments WHERE id = :id")
    suspend fun getMoment(id: String): MomentEntity?

    @Query(
        """
        SELECT * FROM moments
        WHERE assistant_id = :assistantId
        AND author = :author
        AND reply_status = :replyStatus
        AND reply_due_at <= :now
        ORDER BY reply_due_at ASC
        LIMIT :limit
        """
    )
    suspend fun getDueMoments(
        assistantId: String,
        author: String,
        replyStatus: String,
        now: Long,
        limit: Int,
    ): List<MomentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMoment(moment: MomentEntity)

    @Update
    suspend fun updateMoment(moment: MomentEntity)

    @Query("UPDATE moments SET user_liked = NOT user_liked WHERE id = :id")
    suspend fun toggleUserLike(id: String)

    @Transaction
    suspend fun completeReaction(id: String, liked: Boolean, content: String, imageDescription: String?, now: Long): Boolean {
        val current = getMoment(id) ?: return false
        if (current.author != "user" ||
            !(current.replyStatus == "pending" || current.replyStatus == "done" && current.aiReplyContent.isBlank())
        ) return false
        updateMoment(current.copy(
            aiLiked = liked,
            aiReplyContent = content,
            imageDescription = imageDescription ?: current.imageDescription,
            repliedAt = now,
            replyStatus = "done",
        ))
        return true
    }

    @Query("DELETE FROM moments WHERE id = :id")
    suspend fun deleteMoment(id: String)

    @Query("SELECT * FROM moment_comments WHERE moment_id = :momentId ORDER BY created_at ASC")
    fun observeComments(momentId: String): Flow<List<MomentCommentEntity>>

    @Query(
        """
        SELECT c.* FROM moment_comments c
        INNER JOIN moments m ON m.id = c.moment_id
        WHERE m.assistant_id = :assistantId
        ORDER BY c.created_at ASC
        """
    )
    fun observeCommentsForAssistant(assistantId: String): Flow<List<MomentCommentEntity>>

    @Query("SELECT * FROM moment_comments WHERE moment_id = :momentId ORDER BY created_at ASC")
    suspend fun getComments(momentId: String): List<MomentCommentEntity>

    @Query("SELECT * FROM moment_comments WHERE id = :id")
    suspend fun getComment(id: String): MomentCommentEntity?

    @Query(
        """
        SELECT c.* FROM moment_comments c
        INNER JOIN moments m ON m.id = c.moment_id
        WHERE m.assistant_id = :assistantId
        AND c.author = :author
        AND c.reply_status = :replyStatus
        AND c.reply_due_at <= :now
        ORDER BY c.reply_due_at ASC
        LIMIT :limit
        """
    )
    suspend fun getDueComments(
        assistantId: String,
        author: String,
        replyStatus: String,
        now: Long,
        limit: Int,
    ): List<MomentCommentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComment(comment: MomentCommentEntity)

    @Update
    suspend fun updateComment(comment: MomentCommentEntity)

    @Transaction
    suspend fun completeCommentReply(commentId: String, reply: MomentCommentEntity): Boolean {
        val current = getComment(commentId) ?: return false
        if (current.author != "user" || current.momentId != reply.momentId) return false
        val canRepair = current.replyStatus == "done" && getComments(current.momentId).none {
            it.author == "assistant" && it.createdAt > current.createdAt
        }
        if (current.replyStatus != "pending" && !canRepair) return false
        // The pending check, reply insert and completion must commit or roll back together.
        upsertComment(reply.copy(createdAt = maxOf(reply.createdAt, current.createdAt + 1)))
        updateComment(current.copy(replyStatus = "done"))
        return true
    }

    @Query("DELETE FROM moment_comments WHERE id = :id")
    suspend fun deleteComment(id: String)

    @Query("SELECT * FROM moment_profiles WHERE assistant_id = :assistantId")
    fun observeProfile(assistantId: String): Flow<MomentProfileEntity?>

    @Query("SELECT * FROM moment_profiles WHERE assistant_id = :assistantId")
    suspend fun getProfile(assistantId: String): MomentProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: MomentProfileEntity)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM moments
            WHERE assistant_id = :assistantId
            AND author = :aiAuthor
            AND created_at > :lastViewedAt
        )
        OR EXISTS(
            SELECT 1 FROM moments
            WHERE assistant_id = :assistantId
            AND author = :userAuthor
            AND (
                (replied_at IS NOT NULL AND replied_at > :lastViewedAt)
                OR (ai_liked = 1 AND replied_at IS NOT NULL AND replied_at > :lastViewedAt)
            )
        )
        OR EXISTS(
            SELECT 1 FROM moment_comments c
            INNER JOIN moments m ON m.id = c.moment_id
            WHERE m.assistant_id = :assistantId
            AND c.author = :aiAuthor
            AND c.created_at > :lastViewedAt
        )
        """
    )
    fun observeHasUnread(
        assistantId: String,
        lastViewedAt: Long,
        aiAuthor: String,
        userAuthor: String,
    ): Flow<Boolean>
}
