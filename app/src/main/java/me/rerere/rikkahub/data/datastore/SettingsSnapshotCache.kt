package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SettingsSnapshotCache(private val decode: (Preferences) -> Settings) {
    private val mutex = Mutex()
    private var cachedValues: Map<Preferences.Key<*>, Any>? = null
    private var cachedSettings: Settings? = null

    suspend fun read(preferences: Preferences): Settings = withContext(Dispatchers.Default) {
        mutex.withLock {
            // edit() supplies mutable Preferences; retaining its map would make stale settings look current.
            val values = preferences.asMap().toMap()
            cachedSettings?.takeIf { cachedValues == values } ?: decode(preferences).also {
                cachedValues = values
                cachedSettings = it
            }
        }
    }
}
