package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

class SettingsSnapshotCacheTest {
    private val payload = stringPreferencesKey("settings-test-payload")
    private val unrelated = stringPreferencesKey("other-preference")

    @Test
    fun concurrentAndRepeatedReadsDecodeTheSameSnapshotOnceOnBackgroundThread() = runBlocking {
        val calls = AtomicInteger()
        val decoderThread = AtomicLong()
        val callerThread = Thread.currentThread().id
        val encoded = JsonInstant.encodeToString(Settings(themeId = "initial"))
        val cache = SettingsSnapshotCache { preferences ->
            calls.incrementAndGet()
            decoderThread.set(Thread.currentThread().id)
            JsonInstant.decodeFromString<Settings>(requireNotNull(preferences[payload]))
        }
        val snapshots = coroutineScope {
            List(8) {
                async { cache.read(mutablePreferencesOf(payload to encoded)) }
            }.awaitAll()
        }

        snapshots.forEach { assertSame(snapshots.first(), it) }
        assertSame(snapshots.first(), cache.read(mutablePreferencesOf(payload to encoded)))
        assertEquals(1, calls.get())
        assertNotEquals(callerThread, decoderThread.get())
        assertNull(snapshots.first().updateBaseline)
    }

    @Test
    fun editingTheSameMutablePreferencesObjectCannotReuseStaleSettings() = runBlocking {
        val preferences = mutablePreferencesOf(payload to JsonInstant.encodeToString(Settings(themeId = "old")))
        val cache = SettingsSnapshotCache {
            JsonInstant.decodeFromString<Settings>(requireNotNull(it[payload]))
        }
        val old = cache.read(preferences)
        preferences[payload] = JsonInstant.encodeToString(Settings(themeId = "new"))
        val current = cache.read(preferences)

        assertNotSame(old, current)
        assertEquals("old", old.themeId)
        assertEquals("new", current.themeId)
        assertSame(current, cache.read(preferences))
    }

    @Test
    fun assistantAndCounterWritesAreReadFreshAndKeepAUsableEditingBaseline() = runBlocking {
        val initial = Settings(assistantId = Uuid.random(), launchCount = 12)
        val preferences = mutablePreferencesOf(payload to JsonInstant.encodeToString(initial))
        val cache = SettingsSnapshotCache {
            JsonInstant.decodeFromString<Settings>(requireNotNull(it[payload]))
        }
        val oldEditor = cache.read(preferences).withUpdateBaseline()
        val selected = initial.copy(assistantId = Uuid.random())
        preferences[payload] = JsonInstant.encodeToString(selected)
        assertEquals(selected.assistantId, cache.read(preferences).assistantId)
        val counted = selected.copy(launchCount = 13)
        preferences[payload] = JsonInstant.encodeToString(counted)
        val current = cache.read(preferences)
        val merged = mergeSettingsUpdate(current, oldEditor.copy(themeId = "edited"))

        assertEquals(counted.assistantId, current.assistantId)
        assertEquals(13, current.launchCount)
        assertEquals(counted.assistantId, merged.assistantId)
        assertEquals(13, merged.launchCount)
        assertEquals("edited", merged.themeId)
        val freshEditor = current.withUpdateBaseline()
        assertEquals(
            JsonInstant.encodeToJsonElement(current),
            JsonInstant.encodeToJsonElement(mergeSettingsUpdate(current, freshEditor)),
        )
    }

    @Test
    fun changedUnrelatedPreferencesInvalidateAndFailedDecodeDoesNotPoisonTheCache() = runBlocking {
        val calls = AtomicInteger()
        val preferences = mutablePreferencesOf(payload to JsonInstant.encodeToString(Settings(themeId = "valid")))
        val cache = SettingsSnapshotCache {
            calls.incrementAndGet()
            JsonInstant.decodeFromString<Settings>(requireNotNull(it[payload]))
        }
        val first = cache.read(preferences)
        preferences[unrelated] = "changed"
        val changed = cache.read(preferences)
        assertNotSame(first, changed)
        assertEquals(2, calls.get())

        preferences[payload] = "{"
        val failure = runCatching { cache.read(preferences) }.exceptionOrNull()
        assertTrue(failure is SerializationException)
        preferences[payload] = JsonInstant.encodeToString(Settings(themeId = "recovered"))
        val recovered = cache.read(preferences)
        assertEquals("recovered", recovered.themeId)
        assertEquals(4, calls.get())
        assertSame(recovered, cache.read(preferences))
    }
}
