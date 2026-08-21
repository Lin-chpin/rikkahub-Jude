package me.rerere.rikkahub.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local coordination boundary for features that must not run during a live call.
 *
 * The registry deliberately knows only about session IDs. Heartbeat scheduling and the
 * call UI can depend on this boundary without depending on each other's implementation.
 */
object VoiceCallSessionRegistry {
    private val activeSessionIds = ConcurrentHashMap.newKeySet<String>()

    fun register(sessionId: String) {
        activeSessionIds += sessionId
    }

    fun unregister(sessionId: String) {
        activeSessionIds -= sessionId
    }

    fun isActive(): Boolean = activeSessionIds.isNotEmpty()
}
