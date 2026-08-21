package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val CONVERSATION_SCOPE_PREFIX = "conversation:"

/**
 * Moves memories created before assistant-wide memory became the only local-memory scope.
 * Global memories are deliberately left untouched because they have no assistant owner.
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE memoryentity
            SET assistant_id = (
                SELECT conversationentity.assistant_id
                FROM conversationentity
                WHERE conversationentity.id = substr(
                    memoryentity.assistant_id,
                    ${CONVERSATION_SCOPE_PREFIX.length + 1}
                )
            )
            WHERE memoryentity.assistant_id LIKE '${CONVERSATION_SCOPE_PREFIX}%'
              AND EXISTS (
                  SELECT 1
                  FROM conversationentity
                  WHERE conversationentity.id = substr(
                      memoryentity.assistant_id,
                      ${CONVERSATION_SCOPE_PREFIX.length + 1}
                  )
              )
            """.trimIndent()
        )

        // Keep the oldest row for identical memories within each assistant scope.
        db.execSQL(
            """
            DELETE FROM memoryentity
            WHERE memoryentity.assistant_id != '__global__'
              AND EXISTS (
                  SELECT 1
                  FROM memoryentity older
                  WHERE older.assistant_id = memoryentity.assistant_id
                    AND older.content = memoryentity.content
                    AND older.id < memoryentity.id
              )
            """.trimIndent()
        )
    }
}
