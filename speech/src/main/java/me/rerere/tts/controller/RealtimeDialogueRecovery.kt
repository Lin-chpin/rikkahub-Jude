package me.rerere.tts.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.provider.TTSProviderErrorCode
import me.rerere.tts.provider.TTSProviderException

/** Replay text only before any audio escapes. An audible turn must never restart from its beginning. */
internal fun recoverRealtimeDialogue(
    text: Flow<String>,
    connect: (Flow<String>) -> Flow<AudioChunk>,
    fallback: ((String) -> Flow<AudioChunk>)? = null,
): Flow<AudioChunk> = flow {
    coroutineScope {
        val pending = Channel<String>(Channel.BUFFERED)
        val producer = launch {
            try {
                text.collect { pending.send(it) }
                pending.close()
            } catch (error: Throwable) {
                pending.close(error)
                if (error is CancellationException) throw error
            }
        }
        val submitted = StringBuilder()
        val replayable = flow {
            if (submitted.isNotEmpty()) emit(submitted.toString())
            for (delta in pending) {
                submitted.append(delta)
                emit(delta)
            }
        }
        var audioDelivered = false
        try {
            for (attempt in 0..1) {
                try {
                    connect(replayable).collect { chunk ->
                        if (chunk.data.isNotEmpty()) audioDelivered = true
                        emit(chunk)
                    }
                    return@coroutineScope
                } catch (error: Throwable) {
                    if (error is CancellationException || audioDelivered) throw error
                    val permanent = error.causeChain().filterIsInstance<TTSProviderException>().any {
                        it.statusCode in setOf(401, 402, 403) ||
                            it.providerCode in setOf(
                                TTSProviderErrorCode.INVALID_API_KEY,
                                TTSProviderErrorCode.AUTHENTICATION_ERROR,
                                TTSProviderErrorCode.AUTH_ERROR,
                                TTSProviderErrorCode.UNAUTHORIZED,
                                TTSProviderErrorCode.PERMISSION_DENIED,
                                TTSProviderErrorCode.FORBIDDEN,
                                TTSProviderErrorCode.PAYMENT_ISSUE,
                                TTSProviderErrorCode.PAYMENT_REQUIRED,
                                TTSProviderErrorCode.INSUFFICIENT_QUOTA,
                            )
                    }
                    if (permanent) throw error
                    if (attempt == 1) {
                        if (fallback == null) throw error
                        for (delta in pending) submitted.append(delta)
                        if (submitted.isEmpty()) throw error
                        fallback(submitted.toString()).collect { emit(it) }
                    } else delay(250)
                }
            }
        } finally {
            producer.cancel()
            pending.cancel()
        }
    }
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this@causeChain
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}
