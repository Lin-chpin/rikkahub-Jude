package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.repository.AnonymousQuestionAuthor
import me.rerere.rikkahub.data.repository.AnonymousQuestionReplyStatus
import me.rerere.rikkahub.data.repository.AnonymousQuestionRepository
import me.rerere.rikkahub.data.repository.MomentAuthor
import me.rerere.rikkahub.data.repository.MomentReplyStatus
import me.rerere.rikkahub.data.repository.MomentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class SocialReplyTransactionTest {
    @Test
    fun commentCompletionRollsBackAndConcurrentRetriesInsertOnlyOneReply() = runBlocking {
        val database = database()
        try {
            val repository = MomentRepository(database.momentDao())
            val momentId = repository.postUserMoment(Uuid.random(), "Moment", emptyList(), now = 0)
            repository.addUserComment(momentId, "Comment", now = 1)
            val comment = repository.getComments(momentId).single()
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_comment_completion BEFORE UPDATE ON moment_comments
                WHEN OLD.author = 'user' BEGIN SELECT RAISE(ABORT, 'test failure'); END
            """.trimIndent())

            assertNotNull(runCatching { repository.completeCommentReply(comment, "Reply", now = 2) }.exceptionOrNull())
            assertEquals(1, repository.getComments(momentId).size)
            assertEquals(MomentReplyStatus.PENDING, repository.getComments(momentId).single().replyStatus)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_comment_completion")

            val committed = List(2) {
                async(Dispatchers.IO) { repository.completeCommentReply(comment, "Reply", now = 2) }
            }.awaitAll()
            assertEquals(1, committed.count { it })
            assertEquals(1, repository.getComments(momentId).count { it.author == MomentAuthor.ASSISTANT })
            assertEquals(MomentReplyStatus.DONE, repository.getComments(momentId).first().replyStatus)
            database.momentDao().deleteMoment(momentId.toString())
            assertFalse(repository.completeCommentReply(comment, "Late reply", now = 3))
        } finally {
            database.close()
        }
    }

    @Test
    fun anonymousRepliesCommitWithStatusAndDoNotReplaceAnExistingUserAnswer() = runBlocking {
        val database = database()
        try {
            val repository = AnonymousQuestionRepository(database.anonymousQuestionDao())
            val scopeId = Uuid.random()
            val questionId = repository.postUserQuestion(scopeId, "Question", now = 0)
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_question_completion BEFORE UPDATE ON anonymous_questions
                BEGIN SELECT RAISE(ABORT, 'test failure'); END
            """.trimIndent())
            assertNotNull(runCatching { repository.completeQuestionAnswer(questionId, "Answer", now = 1) }.exceptionOrNull())
            assertTrue(repository.getReplies(questionId).isEmpty())
            assertEquals(AnonymousQuestionReplyStatus.PENDING, repository.getQuestion(questionId)?.replyStatus)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_question_completion")
            val completed = List(2) {
                async(Dispatchers.IO) { repository.completeQuestionAnswer(questionId, "Answer", now = 1) }
            }.awaitAll()
            assertEquals(1, completed.count { it })
            assertEquals(1, repository.getReplies(questionId).size)

            val assistantQuestion = repository.postAssistantQuestion(scopeId, "Assistant question", now = 2)
            val answers = List(2) { index ->
                async(Dispatchers.IO) { repository.addUserAnswer(assistantQuestion, "User answer $index", now = 3) }
            }.awaitAll()
            assertEquals(1, answers.count { it != null })
            val answer = repository.getReplies(assistantQuestion).single()
            assertEquals(AnonymousQuestionAuthor.USER, answer.author)
            assertTrue(repository.completeAnswerComment(answer, "Comment", now = 4))
            assertFalse(repository.completeAnswerComment(answer, "Late overwrite", now = 5))
            assertEquals(2, repository.getReplies(assistantQuestion).size)
            assertEquals("Comment", repository.getReplies(assistantQuestion).last().content)
        } finally {
            database.close()
        }
    }

    private fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).build()
}
