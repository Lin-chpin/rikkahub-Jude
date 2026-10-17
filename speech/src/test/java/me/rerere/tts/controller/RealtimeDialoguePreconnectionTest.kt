package me.rerere.tts.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class RealtimeDialoguePreconnectionTest {
    @Test
    fun preparesWithoutSynthesisThenUsesTheSameConnectionAndCancelsUnusedConnections() = runBlocking {
        withTimeout(5_000) {
            val provider = TTSProviderSetting.ElevenLabs(model = "eleven_v4", voiceId = "voice")
            val connections = AtomicInteger()
            val submittedText = ConcurrentLinkedQueue<String>()
            val opened = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val prepared = RealtimeDialoguePreconnection(this, provider, generateAudio = { text ->
                flow {
                    connections.incrementAndGet()
                    opened.complete(Unit)
                    try {
                        text.collect {
                            submittedText.add(it)
                            emit(AudioChunk(it.toByteArray(), AudioFormat.MP3))
                        }
                    } finally {
                        closed.complete(Unit)
                    }
                }
            })
            try {
                opened.await()
                assertTrue(submittedText.isEmpty())
                assertTrue(prepared.matches(provider))
                assertFalse(prepared.matches(provider.copy(voiceId = "another-voice")))
                val audio = prepared.audioFor(flowOf("你好。", "下一句。")).toList()
                closed.await()
                assertEquals(1, connections.get())
                assertEquals(listOf("你好。", "下一句。"), submittedText.toList())
                assertEquals(2, audio.size)
            } finally {
                prepared.close()
            }

            val idleOpened = CompletableDeferred<Unit>()
            val idleClosed = CompletableDeferred<Unit>()
            val unused = RealtimeDialoguePreconnection(this, provider, generateAudio = { text ->
                flow {
                    idleOpened.complete(Unit)
                    try {
                        text.collect {
                            submittedText.add(it)
                            emit(AudioChunk(it.toByteArray(), AudioFormat.MP3))
                        }
                    } finally {
                        idleClosed.complete(Unit)
                    }
                }
            })
            try {
                idleOpened.await()
                unused.close()
                idleClosed.await()
                assertFalse(unused.matches(provider))
                assertEquals(listOf("你好。", "下一句。"), submittedText.toList())
            } finally {
                unused.close()
            }
        }
    }
}
