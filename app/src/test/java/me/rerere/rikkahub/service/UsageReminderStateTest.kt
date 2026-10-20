package me.rerere.rikkahub.service

import me.rerere.usagetracker.UsageReminderLock
import me.rerere.usagetracker.UsageReminderState
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageReminderStateTest {
    @Suppress("DEPRECATION")
    @Test
    fun legacyLockAndSeparateAppLocksArePreserved() {
        val first = UsageReminderLock(
            lockedUntilMillis = 1_000L,
            source = "ai_tool",
            targetPackageName = "com.example.first",
        )
        val second = first.copy(targetPackageName = "com.example.second", lockedUntilMillis = 2_000L)

        val legacyState = Json.decodeFromString<UsageReminderState>(
            """{"date":"2026-10-20","appStates":{},"activeLock":{"lockedUntilMillis":1000,"source":"ai_tool","targetPackageName":"com.example.first"}}"""
        )
        val migrated = legacyState.withLocks(
            legacyState.allLocks() + (second.storageKey() to second)
        )

        assertNull(migrated.activeLock)
        assertEquals(setOf("com.example.first", "com.example.second"), migrated.allLocks().keys)
    }
}
