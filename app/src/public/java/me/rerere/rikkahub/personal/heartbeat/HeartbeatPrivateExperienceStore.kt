package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HeartbeatPrivateExperience(
    val createdAtMillis: Long,
    val conversationId: String?,
    val outcome: String,
    val pressure: Double? = null,
    val score: Double? = null,
    val text: String? = null,
)

/** Stores internal heartbeat decisions separately from the shared chat timeline. */
class HeartbeatPrivateExperienceStore(
    context: Context,
    private val assistantId: String? = null,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun append(experience: HeartbeatPrivateExperience) {
        val entries = read().takeLast(MAX_ENTRIES - 1) + experience
        preferences.edit()
            .putString(experienceKey(), json.encodeToString(entries))
            .apply()
    }

    fun recentDeliveredTexts(limit: Int = 20): List<String> = read()
        .asSequence()
        .filter { it.outcome == OUTCOME_SENT }
        .mapNotNull { it.text }
        .filter(String::isNotBlank)
        .toList()
        .takeLast(limit)

    private fun read(): List<HeartbeatPrivateExperience> {
        val raw = preferences.getString(experienceKey(), null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<HeartbeatPrivateExperience>>(raw)
        }.getOrDefault(emptyList())
    }

    companion object {
        const val OUTCOME_SENT = "SENT"
        private const val PREFERENCES_NAME = "personal_heartbeat_experience"
        private const val EXPERIENCE_KEY = "entries"
        private const val MAX_ENTRIES = 200
    }

    private fun experienceKey(): String =
        assistantId?.let { "${EXPERIENCE_KEY}_$it" } ?: EXPERIENCE_KEY
}
