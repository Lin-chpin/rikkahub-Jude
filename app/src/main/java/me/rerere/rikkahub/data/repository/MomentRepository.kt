package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import me.rerere.rikkahub.data.db.dao.MomentDAO
import me.rerere.rikkahub.data.db.entity.MomentCommentEntity
import me.rerere.rikkahub.data.db.entity.MomentEntity
import me.rerere.rikkahub.data.db.entity.MomentProfileEntity
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.random.Random
import kotlin.uuid.Uuid

class MomentRepository(
    private val dao: MomentDAO,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTimeline(assistantId: Uuid): Flow<List<MomentEntry>> {
        val assistantKey = assistantId.toString()
        return combine(
            dao.observeMoments(assistantKey),
            dao.observeCommentsForAssistant(assistantKey),
        ) { moments, comments ->
            val commentsByMoment = comments.groupBy { it.momentId }
            moments.map { moment ->
                MomentEntry(
                    moment = moment.toMoment(),
                    comments = commentsByMoment[moment.id].orEmpty().map { it.toMomentComment() },
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeProfile(assistantId: Uuid): Flow<MomentProfile> {
        return dao.observeProfile(assistantId.toString()).mapLatest {
            it?.toMomentProfile() ?: MomentProfile(assistantId = assistantId)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeHasUnread(assistantId: Uuid): Flow<Boolean> {
        return dao.observeProfile(assistantId.toString()).flatMapLatest { profile ->
            dao.observeHasUnread(
                assistantId = assistantId.toString(),
                lastViewedAt = profile?.lastViewedAt ?: 0L,
                aiAuthor = MomentAuthor.ASSISTANT.value,
                userAuthor = MomentAuthor.USER.value,
            )
        }
    }

    suspend fun getTimeline(assistantId: Uuid): List<MomentEntry> {
        return dao.getMoments(assistantId.toString()).map { moment ->
            MomentEntry(
                moment = moment.toMoment(),
                comments = dao.getComments(moment.id).map { it.toMomentComment() },
            )
        }
    }

    suspend fun getMoment(id: Uuid): Moment? = dao.getMoment(id.toString())?.toMoment()

    suspend fun deleteMoments(
        assistantId: Uuid,
        momentId: Uuid?,
        keyword: String,
        latest: Boolean,
        limit: Int,
    ): List<Moment> {
        val assistantKey = assistantId.toString()
        val candidates = when {
            momentId != null -> listOfNotNull(dao.getMoment(momentId.toString()))
                .filter { it.assistantId == assistantKey }

            keyword.isNotBlank() -> dao.getMoments(assistantKey).filter { moment ->
                listOf(
                    moment.content,
                    moment.contextNote,
                    moment.imageDescription,
                    moment.aiReplyContent,
                ).any { it.contains(keyword, ignoreCase = true) }
            }

            latest -> dao.getMoments(assistantKey).take(1)
            else -> emptyList()
        }.take(limit.coerceIn(1, 20))

        candidates.forEach { dao.deleteMoment(it.id) }
        return candidates.map { it.toMoment() }
    }

    suspend fun postAssistantMoment(
        assistantId: Uuid,
        content: String,
        contextNote: String,
        createdAt: Long = System.currentTimeMillis(),
    ): Uuid {
        val id = Uuid.random()
        dao.upsertMoment(
            MomentEntity(
                id = id.toString(),
                assistantId = assistantId.toString(),
                author = MomentAuthor.ASSISTANT.value,
                content = content.trim(),
                contextNote = contextNote.trim(),
                imageDescription = "",
                images = JsonInstant.encodeToString(emptyList<String>()),
                replyDueAt = createdAt,
                replyStatus = MomentReplyStatus.DONE.value,
                aiLiked = false,
                aiReplyContent = "",
                repliedAt = null,
                aiReplySeenAt = null,
                userLiked = false,
                createdAt = createdAt,
            )
        )
        return id
    }

    suspend fun postUserMoment(
        assistantId: Uuid,
        content: String,
        imageUris: List<String>,
        now: Long = System.currentTimeMillis(),
    ): Uuid {
        val id = Uuid.random()
        dao.upsertMoment(
            MomentEntity(
                id = id.toString(),
                assistantId = assistantId.toString(),
                author = MomentAuthor.USER.value,
                content = content.trim(),
                contextNote = "",
                imageDescription = "",
                images = JsonInstant.encodeToString(imageUris.take(MAX_IMAGES)),
                replyDueAt = now + Random.nextLong(10, 21) * 60_000L,
                replyStatus = MomentReplyStatus.PENDING.value,
                aiLiked = false,
                aiReplyContent = "",
                repliedAt = null,
                aiReplySeenAt = null,
                userLiked = false,
                createdAt = now,
            )
        )
        return id
    }

    suspend fun completeReaction(
        momentId: Uuid,
        liked: Boolean,
        content: String,
        imageDescription: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = dao.completeReaction(momentId.toString(), liked, content, imageDescription, now)

    suspend fun toggleUserLike(momentId: Uuid) {
        dao.toggleUserLike(momentId.toString())
    }

    suspend fun addUserComment(momentId: Uuid, content: String, now: Long = System.currentTimeMillis()): Uuid {
        val id = Uuid.random()
        dao.upsertComment(
            MomentCommentEntity(
                id = id.toString(),
                momentId = momentId.toString(),
                author = MomentAuthor.USER.value,
                content = content.trim(),
                replyDueAt = now + Random.nextLong(3, 9) * 60_000L,
                replyStatus = MomentReplyStatus.PENDING.value,
                seenAt = null,
                createdAt = now,
            )
        )
        return id
    }

    suspend fun completeCommentReply(comment: MomentComment, content: String, now: Long = System.currentTimeMillis()): Boolean =
        dao.completeCommentReply(
            comment.id.toString(),
            MomentCommentEntity(
                id = Uuid.random().toString(),
                momentId = comment.momentId.toString(),
                author = MomentAuthor.ASSISTANT.value,
                content = content.trim(),
                replyDueAt = null,
                replyStatus = MomentReplyStatus.NONE.value,
                seenAt = null,
                createdAt = now,
            )
        )

    suspend fun markViewed(assistantId: Uuid, viewedAt: Long = System.currentTimeMillis()) {
        val current = dao.getProfile(assistantId.toString())
        dao.upsertProfile(
            MomentProfileEntity(
                assistantId = assistantId.toString(),
                coverUri = current?.coverUri.orEmpty(),
                lastViewedAt = viewedAt,
            )
        )
    }

    suspend fun updateCover(assistantId: Uuid, coverUri: String) {
        val current = dao.getProfile(assistantId.toString())
        dao.upsertProfile(
            MomentProfileEntity(
                assistantId = assistantId.toString(),
                coverUri = coverUri,
                lastViewedAt = current?.lastViewedAt ?: 0L,
            )
        )
    }

    suspend fun getDueUserMoments(assistantId: Uuid, now: Long, limit: Int): List<Moment> =
        dao.getDueMoments(
            assistantId = assistantId.toString(),
            author = MomentAuthor.USER.value,
            replyStatus = MomentReplyStatus.PENDING.value,
            now = now,
            limit = limit,
        ).map { it.toMoment() }

    suspend fun getRefreshUserMoments(assistantId: Uuid, limit: Int): List<Moment> {
        return dao.getMoments(assistantId.toString())
            .filter { moment ->
                moment.author == MomentAuthor.USER.value && (
                    moment.replyStatus == MomentReplyStatus.PENDING.value ||
                        moment.replyStatus == MomentReplyStatus.DONE.value && moment.aiReplyContent.isBlank()
                    )
            }
            .sortedBy { it.replyDueAt }
            .take(limit)
            .map { it.toMoment() }
    }

    suspend fun getDueUserComments(assistantId: Uuid, now: Long, limit: Int): List<MomentComment> =
        dao.getDueComments(
            assistantId = assistantId.toString(),
            author = MomentAuthor.USER.value,
            replyStatus = MomentReplyStatus.PENDING.value,
            now = now,
            limit = limit,
        ).map { it.toMomentComment() }

    suspend fun getRefreshUserComments(assistantId: Uuid, limit: Int): List<MomentComment> {
        val candidates = dao.getMoments(assistantId.toString()).flatMap { moment ->
            val comments = dao.getComments(moment.id)
            comments.filter { comment ->
                val isUserComment = comment.author == MomentAuthor.USER.value
                val isPending = comment.replyStatus == MomentReplyStatus.PENDING.value
                val isCompletedWithoutReply = comment.replyStatus == MomentReplyStatus.DONE.value &&
                    comments.none {
                        it.author == MomentAuthor.ASSISTANT.value && it.createdAt > comment.createdAt
                    }
                isUserComment && (isPending || isCompletedWithoutReply)
            }
        }
        return candidates
            .sortedWith(compareBy({ it.replyDueAt ?: Long.MAX_VALUE }, { it.createdAt }))
            .take(limit)
            .map { it.toMomentComment() }
    }

    suspend fun getComments(momentId: Uuid): List<MomentComment> =
        dao.getComments(momentId.toString()).map { it.toMomentComment() }

    companion object {
        const val MAX_IMAGES = 4
    }
}

data class MomentEntry(
    val moment: Moment,
    val comments: List<MomentComment>,
)

data class MomentProfile(
    val assistantId: Uuid,
    val coverUri: String = "",
    val lastViewedAt: Long = 0L,
)

data class Moment(
    val id: Uuid,
    val assistantId: Uuid,
    val author: MomentAuthor,
    val content: String,
    val contextNote: String,
    val imageDescription: String,
    val imageUris: List<String>,
    val replyDueAt: Long,
    val replyStatus: MomentReplyStatus,
    val aiLiked: Boolean,
    val aiReplyContent: String,
    val repliedAt: Long?,
    val aiReplySeenAt: Long?,
    val userLiked: Boolean,
    val createdAt: Long,
)

data class MomentComment(
    val id: Uuid,
    val momentId: Uuid,
    val author: MomentAuthor,
    val content: String,
    val replyDueAt: Long?,
    val replyStatus: MomentReplyStatus,
    val seenAt: Long?,
    val createdAt: Long,
)

enum class MomentAuthor(val value: String) {
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun of(value: String): MomentAuthor = entries.firstOrNull { it.value == value } ?: USER
    }
}

enum class MomentReplyStatus(val value: String) {
    NONE("none"),
    PENDING("pending"),
    DONE("done");

    companion object {
        fun of(value: String): MomentReplyStatus = entries.firstOrNull { it.value == value } ?: NONE
    }
}

private fun MomentEntity.toMoment(): Moment = Moment(
    id = Uuid.parse(id),
    assistantId = Uuid.parse(assistantId),
    author = MomentAuthor.of(author),
    content = content,
    contextNote = contextNote,
    imageDescription = imageDescription,
    imageUris = JsonInstant.decodeFromString(images),
    replyDueAt = replyDueAt,
    replyStatus = MomentReplyStatus.of(replyStatus),
    aiLiked = aiLiked,
    aiReplyContent = aiReplyContent,
    repliedAt = repliedAt,
    aiReplySeenAt = aiReplySeenAt,
    userLiked = userLiked,
    createdAt = createdAt,
)

private fun MomentCommentEntity.toMomentComment(): MomentComment = MomentComment(
    id = Uuid.parse(id),
    momentId = Uuid.parse(momentId),
    author = MomentAuthor.of(author),
    content = content,
    replyDueAt = replyDueAt,
    replyStatus = MomentReplyStatus.of(replyStatus),
    seenAt = seenAt,
    createdAt = createdAt,
)

private fun MomentProfileEntity.toMomentProfile(): MomentProfile = MomentProfile(
    assistantId = Uuid.parse(assistantId),
    coverUri = coverUri,
    lastViewedAt = lastViewedAt,
)
