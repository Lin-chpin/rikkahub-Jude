package me.rerere.tts.controller

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.forRealtimeCall
import org.junit.Assert.*
import org.junit.Test

class RealtimeDialogueRecoveryTest {
    private fun audio(value: String) = AudioChunk(value.toByteArray(), AudioFormat.MP3)

    @Test fun reconnectReplaysConsumedTextBeforeAnyAudioAndKeepsOriginalInputOrder() = runBlocking {
        val attempts = AtomicInteger()
        val texts = mutableListOf<String>()
        val result = recoverRealtimeDialogue(flowOf("第一句，", "后半句。"), connect = { input ->
            flow {
                val script = StringBuilder()
                input.collect { script.append(it) }
                texts += script.toString()
                if (attempts.incrementAndGet() == 1) throw IOException("disconnect")
                emit(audio(script.toString()))
            }
        }).toList()
        assertEquals(listOf("第一句，后半句。", "第一句，后半句。"), texts)
        assertEquals("第一句，后半句。", String(result.single().data))
    }

    @Test fun neverReconnectsOrFallsBackAfterAudioHasEscaped() = runBlocking {
        var connections = 0
        var fallbacks = 0
        val heard = mutableListOf<String>()
        try {
            recoverRealtimeDialogue(flowOf("reply"), connect = {
                flow {
                    connections++
                    emit(audio("prefix"))
                    throw IOException("late disconnect")
                }
            }, fallback = { fallbacks++; flowOf(audio("duplicate")) }).collect { heard += String(it.data) }
            fail("An interrupted audible stream must fail")
        } catch (_: IOException) { }
        assertEquals(listOf("prefix"), heard)
        assertEquals(1, connections)
        assertEquals(0, fallbacks)
    }

    @Test fun cancelsOldSocketAndNeverDeliversItsLateAudioToTheNewTurn() = runBlocking {
        withTimeout(5_000) {
            val opened = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val oldAudio = mutableListOf<AudioChunk>()
            val old = launch {
                recoverRealtimeDialogue(flowOf("old"), connect = {
                    flow {
                        opened.complete(Unit)
                        try { awaitCancellation() } finally { closed.complete(Unit) }
                    }
                }).collect { oldAudio += it }
            }
            opened.await()
            old.cancelAndJoin()
            closed.await()
            val next = recoverRealtimeDialogue(flowOf("new"), connect = { input ->
                flow { input.collect { emit(audio(it)) } }
            }).toList()
            assertTrue(oldAudio.isEmpty())
            assertEquals("new", String(next.single().data))
        }
    }

    @Test fun usesFallbackOnlyBeforeAudioAndRetainsTheWholeScript() = runBlocking {
        var attempts = 0
        var fallbackText = ""
        val result = recoverRealtimeDialogue(flowOf("hello", " world"), connect = { input ->
            flow {
                input.collect { }
                attempts++
                throw IOException("socket unavailable")
            }
        }, fallback = {
            fallbackText = it
            flowOf(audio(it))
        }).toList()
        assertEquals(2, attempts)
        assertEquals("hello world", fallbackText)
        assertEquals("hello world", String(result.single().data))
    }

    @Test fun turboOverrideIsLimitedToOptedInV4Calls() {
        val saved = TTSProviderSetting.ElevenLabs(model = "eleven_v4", voiceId = "configured", fullDuplex = true)
        assertEquals("eleven_v4_turbo", saved.forRealtimeCall().model)
        assertEquals("configured", saved.forRealtimeCall().voiceId)
        assertEquals("eleven_v4", saved.model)
        assertEquals("eleven_v4", saved.copy(fullDuplex = false).forRealtimeCall().model)
        assertEquals("eleven_v3", saved.copy(model = "eleven_v3").forRealtimeCall().model)
    }

    @Test fun interruptedCaptureHasAnImmutableSnapshotAndAnAudibleBoundary() {
        val capture = RealtimeDialogueCapture()
        capture.append(audio("first"))
        val prefix = capture.snapshot(123)
        capture.append(audio("late"))
        assertEquals("first", String(prefix.audioData))
        assertEquals("123", prefix.metadata["interrupted_at_ms"])
        assertEquals("firstlate", String(capture.snapshot().audioData))
    }
}
