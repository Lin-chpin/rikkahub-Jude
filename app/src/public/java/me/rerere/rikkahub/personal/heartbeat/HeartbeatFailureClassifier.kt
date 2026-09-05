package me.rerere.rikkahub.personal.heartbeat

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object HeartbeatFailureClassifier {
    fun classify(error: Throwable): HeartbeatRunReason {
        val causes = error.causeChain().toList()
        val searchable = causes.joinToString(" ") { cause ->
            "${cause::class.qualifiedName.orEmpty()} ${cause.message.orEmpty()}"
        }.lowercase()

        return when {
            listOf("401", "unauthorized", "authentication", "invalid api key", "invalid token")
                .any(searchable::contains) -> HeartbeatRunReason.AUTHENTICATION_FAILED
            listOf("429", "rate limit", "too many requests")
                .any(searchable::contains) -> HeartbeatRunReason.RATE_LIMITED
            causes.any { it is SocketTimeoutException } || searchable.contains("timeout") -> {
                HeartbeatRunReason.NETWORK_TIMEOUT
            }
            listOf("sqlite", "database", "disk", "no space", "enospc")
                .any(searchable::contains) -> HeartbeatRunReason.STORAGE_FAILURE
            causes.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketException ||
                    it is SSLException ||
                    it is IOException
            } -> HeartbeatRunReason.NETWORK_UNAVAILABLE
            listOf("tool call", "tool execution", "execute tool")
                .any(searchable::contains) -> HeartbeatRunReason.TOOL_EXECUTION_FAILURE
            else -> HeartbeatRunReason.GENERATION_FAILURE
        }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = this@causeChain
        while (current != null && visited.add(current)) {
            yield(current)
            current = current.cause
        }
    }
}
