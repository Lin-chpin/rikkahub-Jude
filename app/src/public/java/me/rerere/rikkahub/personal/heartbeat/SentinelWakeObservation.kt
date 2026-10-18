package me.rerere.rikkahub.personal.heartbeat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessagePart

/** Repeated/parallel calls in one wake share even an unsuccessful observation. */
internal class SentinelWakeObservation {
    private val mutex = Mutex()
    private var result: List<UIMessagePart>? = null
    suspend fun get(observe: suspend () -> List<UIMessagePart>): List<UIMessagePart> = mutex.withLock {
        result ?: observe().also { result = it }
    }
}
