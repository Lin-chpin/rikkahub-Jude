package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node -> indexNode(conversation, node) }
    }

    suspend fun updateConversation(
        conversation: Conversation,
        changedNodeIds: Set<String>,
    ) = withContext(Dispatchers.IO) {
        if (changedNodeIds.isEmpty()) return@withContext

        val database = db
        val conversationId = conversation.id.toString()
        database.beginTransaction()
        try {
            changedNodeIds.forEach { nodeId ->
                database.execSQL(
                    "DELETE FROM message_fts WHERE conversation_id = ? AND node_id = ?",
                    arrayOf(conversationId, nodeId),
                )
            }
            conversation.messageNodes
                .filter { it.id.toString() in changedNodeIds }
                .forEach { node -> indexNode(database, conversation, node) }

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(keyword: String): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val cursor = db.query(
            """
            SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id,
                   conversationentity.title, conversationentity.update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            JOIN conversationentity ON conversationentity.id = message_fts.conversation_id
            WHERE message_fts.text MATCH jieba_query(?)
            ORDER BY message_fts.rank, conversationentity.update_at DESC
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword)
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }

    private fun indexNode(conversation: Conversation, node: MessageNode) = indexNode(db, conversation, node)

    private fun indexNode(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        conversation: Conversation,
        node: MessageNode,
    ) {
        val conversationId = conversation.id.toString()
        node.messages.forEach { message ->
            val text = message.extractFtsText()
            if (text.isNotBlank()) {
                database.execSQL(
                    "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        text,
                        node.id.toString(),
                        message.id.toString(),
                        conversationId,
                        conversation.title,
                        conversation.updateAt.toEpochMilli().toString(),
                    )
                )
            }
        }
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
