package me.rerere.rikkahub.personal.heartbeat

import org.junit.Assert.*
import org.junit.Test

class SentinelPrivacyTest {
    @Test fun `blocked split screen and uncertain ownership prevent capture`() {
        val blocked = setOf("private.app")
        assertEquals("privacy_blocked", SentinelScreenSnapshot(1, mapOf(1 to "normal.app", 2 to "private.app")).blockReason(blocked))
        assertEquals("screen_app_unknown", SentinelScreenSnapshot(1, mapOf(1 to null)).blockReason(blocked))
        assertEquals("screen_app_unknown", SentinelScreenSnapshot(1, emptyMap()).blockReason(blocked))
        assertNull(SentinelScreenSnapshot(1, mapOf(1 to "normal.app")).blockReason(blocked))
        val before = SentinelScreenSnapshot(1, mapOf(1 to "normal.app"))
        // A sensitive window appearing and disappearing still invalidates that capture.
        assertTrue(before.copy(revision = 3).changedSince(before))
        assertTrue(before.copy(windows = mapOf(2 to "normal.app")).changedSince(before))
        assertEquals("此处为敏感隐私内容，禁止截图", SENTINEL_PRIVACY_MESSAGE)
    }
}
