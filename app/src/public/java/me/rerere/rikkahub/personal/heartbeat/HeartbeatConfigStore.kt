package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import me.rerere.common.android.Logging

private const val HEARTBEAT_LOG_TAG = "Heartbeat"

/** SharedPreferences-backed heartbeat state, optionally isolated to one assistant. */
class HeartbeatConfigStore(
    context: Context,
    private val assistantId: String? = null,
    private val observeChanges: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutableConfig = MutableStateFlow(readFromDisk())
    private val mutableNextTriggerAt = MutableStateFlow(readNextTriggerAtFromDisk())
    private val mutableRunStatus = MutableStateFlow(readRunStatusFromDisk())
    private val mutableDiagnostics = MutableStateFlow(readDiagnosticsFromDisk())
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key == configKey() -> mutableConfig.value = readFromDisk()
            key == scopedKey(NEXT_TRIGGER_KEY) -> mutableNextTriggerAt.value = readNextTriggerAtFromDisk()
            key in setOf(
                scopedKey(RUN_PHASE_KEY),
                scopedKey(RUN_REASON_KEY),
                scopedKey(RUN_ASSISTANT_ID_KEY),
                scopedKey(RUN_DETAIL_KEY),
                scopedKey(RUN_TRIGGER_SOURCE_KEY),
                scopedKey(RUN_STARTED_AT_KEY),
                scopedKey(RUN_UPDATED_AT_KEY),
                scopedKey(RUN_DURATION_KEY),
            ) -> mutableRunStatus.value = readRunStatusFromDisk()
            key in setOf(
                scopedKey(DIAGNOSTICS_KEY),
                scopedKey(STATE_RECOVERY_AT_KEY),
                scopedKey(STATE_RECOVERY_AREA_KEY),
            ) -> mutableDiagnostics.value = readDiagnosticsFromDisk()
        }
    }

    init {
        if (observeChanges) preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    val config: StateFlow<HeartbeatConfig> = mutableConfig.asStateFlow()
    val nextTriggerAt: StateFlow<Long?> = mutableNextTriggerAt.asStateFlow()
    val runStatus: StateFlow<HeartbeatRunStatus> = mutableRunStatus.asStateFlow()
    val diagnostics: StateFlow<HeartbeatRunDiagnostics> = mutableDiagnostics.asStateFlow()

    fun read(): HeartbeatConfig = readFromDisk()

    fun update(config: HeartbeatConfig) {
        val normalized = config.normalized()
        if (assistantId == null && normalized.assistantId != null) {
            HeartbeatConfigStore(appContext, normalized.assistantId).update(normalized)
            return
        }
        preferences.edit()
            .putString(configKey(), json.encodeToString(HeartbeatConfig.serializer(), normalized))
            .also { editor ->
                normalized.assistantId?.let { id ->
                    editor.putStringSet(CONFIG_ASSISTANT_IDS_KEY, knownAssistantIds() + id)
                }
            }
            .apply()
        mutableConfig.value = normalized
    }

    fun readForAssistant(id: String): HeartbeatConfig =
        HeartbeatConfigStore(appContext, id).read()

    fun updateForAssistant(config: HeartbeatConfig) {
        require(!config.assistantId.isNullOrBlank()) {
            "assistantId is required for assistant-scoped heartbeat config"
        }
        HeartbeatConfigStore(appContext, config.assistantId).update(config)
    }

    fun readAllConfigs(): List<HeartbeatConfig> {
        val ids = knownAssistantIds().toMutableSet()
        readLegacyConfig()?.assistantId?.let(ids::add)
        return ids.map { readForAssistant(it) }
    }

    fun recordNextTriggerAt(timestampMillis: Long?) {
        preferences.edit().apply {
            if (timestampMillis == null) remove(scopedKey(NEXT_TRIGGER_KEY))
            else putLong(scopedKey(NEXT_TRIGGER_KEY), timestampMillis)
        }.apply()
        mutableNextTriggerAt.value = timestampMillis
    }

    fun readNextTriggerAt(): Long? = readNextTriggerAtFromDisk()

    fun readDesireState(): HeartbeatDesireState {
        val raw = preferences.getString(scopedKey(DESIRE_STATE_KEY), null)
            ?: return HeartbeatDesireState()
        return runCatching { json.decodeFromString<HeartbeatDesireState>(raw) }
            .getOrElse { error ->
                recordStateRecovery("desire_state", DESIRE_STATE_KEY, error)
                HeartbeatDesireState()
            }
    }

    fun recordDesireState(state: HeartbeatDesireState) {
        preferences.edit()
            .putString(scopedKey(DESIRE_STATE_KEY), json.encodeToString(state))
            .apply()
    }

    private fun readNextTriggerAtFromDisk(): Long? =
        preferences.getLong(scopedKey(NEXT_TRIGGER_KEY), 0L).takeIf { it > 0L }

    fun lastClaimAt(): Long = preferences.getLong(scopedKey(LAST_CLAIM_KEY), 0L)

    fun recordLastClaimAt(timestampMillis: Long) {
        preferences.edit().putLong(scopedKey(LAST_CLAIM_KEY), timestampMillis).commit()
    }

    fun isGoodNightActive(): Boolean =
        preferences.getBoolean(scopedKey(GOOD_NIGHT_ACTIVE_KEY), false)

    fun setGoodNightActive(active: Boolean) {
        preferences.edit().putBoolean(scopedKey(GOOD_NIGHT_ACTIVE_KEY), active).apply()
    }

    fun goodNightNoUsageRuns(): Int =
        preferences.getInt(scopedKey(GOOD_NIGHT_NO_USAGE_RUNS_KEY), 0)

    fun setGoodNightNoUsageRuns(runs: Int) {
        preferences.edit().putInt(scopedKey(GOOD_NIGHT_NO_USAGE_RUNS_KEY), runs).apply()
    }

    fun lastUserMessageAt(): Long =
        preferences.getLong(scopedKey(LAST_USER_MESSAGE_AT_KEY), 0L)

    fun setLastUserMessageAt(timestampMillis: Long) {
        val monotonicTimestamp = maxOf(lastUserMessageAt(), timestampMillis)
        preferences.edit()
            .putLong(scopedKey(LAST_USER_MESSAGE_AT_KEY), monotonicTimestamp)
            .apply()
    }

    fun lastAssistantMessageAt(): Long =
        preferences.getLong(scopedKey(LAST_ASSISTANT_MESSAGE_AT_KEY), 0L)

    fun setLastAssistantMessageAt(timestampMillis: Long) {
        val monotonicTimestamp = maxOf(lastAssistantMessageAt(), timestampMillis)
        preferences.edit()
            .putLong(scopedKey(LAST_ASSISTANT_MESSAGE_AT_KEY), monotonicTimestamp)
            .apply()
    }

    fun recordRunStatus(
        phase: HeartbeatRunPhase,
        detail: String? = null,
        reason: HeartbeatRunReason = HeartbeatRunReason.NONE,
        assistantId: String? = null,
        triggerSource: String? = null,
        startedAtMillis: Long? = null,
        completionImpact: HeartbeatRunImpact? = null,
    ) {
        // Keep the complete exception/cause detail so Android service failures can be diagnosed.
        val normalizedSource = triggerSource?.take(40)
        val storedAssistantId = readRunStatusFromDisk().assistantId
        val normalizedAssistantId = assistantId ?: this.assistantId ?: storedAssistantId
        val now = System.currentTimeMillis()
        val status = HeartbeatRunStatus(
            phase = phase,
            reason = reason,
            assistantId = normalizedAssistantId,
            detail = detail,
            triggerSource = normalizedSource,
            startedAtMillis = startedAtMillis,
            updatedAtMillis = now,
            durationMillis = startedAtMillis?.let { (now - it).coerceAtLeast(0L) },
        )
        val editor = preferences.edit()
            .putString(scopedKey(RUN_PHASE_KEY), status.phase.name)
            .putString(scopedKey(RUN_REASON_KEY), status.reason.name)
            .putString(scopedKey(RUN_ASSISTANT_ID_KEY), status.assistantId)
            .putString(scopedKey(RUN_DETAIL_KEY), status.detail)
            .putString(scopedKey(RUN_TRIGGER_SOURCE_KEY), status.triggerSource)
            .putLong(scopedKey(RUN_UPDATED_AT_KEY), requireNotNull(status.updatedAtMillis))
        status.startedAtMillis?.let { editor.putLong(scopedKey(RUN_STARTED_AT_KEY), it) }
            ?: editor.remove(scopedKey(RUN_STARTED_AT_KEY))
        status.durationMillis?.let { editor.putLong(scopedKey(RUN_DURATION_KEY), it) }
            ?: editor.remove(scopedKey(RUN_DURATION_KEY))

        val updatedDiagnostics = completionImpact?.let { impact ->
            readDiagnosticsFromDisk().afterCompletion(
                impact = impact,
                reason = reason,
                detail = detail,
                completedAtMillis = now,
                durationMillis = status.durationMillis,
                triggerSource = normalizedSource,
            )
        }
        if (updatedDiagnostics != null) {
            editor.putString(
                scopedKey(DIAGNOSTICS_KEY),
                json.encodeToString(HeartbeatRunDiagnostics.serializer(), updatedDiagnostics),
            )
        }
        editor.commit()
        mutableRunStatus.value = status
        if (updatedDiagnostics != null) mutableDiagnostics.value = updatedDiagnostics
        Logging.log(
            tag = HEARTBEAT_LOG_TAG,
            message = buildString {
                append("phase=")
                append(phase.name)
                append(" reason=")
                append(reason.name)
                normalizedSource?.let { append(" source=").append(it) }
                detail?.let { append(" detail=").append(it) }
            },
        )
    }

    fun close() {
        if (observeChanges) preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun readRunStatus(): HeartbeatRunStatus = readRunStatusFromDisk()
    fun readDiagnostics(): HeartbeatRunDiagnostics = readDiagnosticsFromDisk()

    fun clearPendingRetry() {
        val updated = readDiagnosticsFromDisk().copy(nextRetryAtMillis = null)
        preferences.edit()
            .putString(
                scopedKey(DIAGNOSTICS_KEY),
                json.encodeToString(HeartbeatRunDiagnostics.serializer(), updated),
            )
            .apply()
        mutableDiagnostics.value = updated
    }

    private fun readFromDisk(): HeartbeatConfig {
        val raw = preferences.getString(configKey(), null)
            ?: if (assistantId != null) {
                readLegacyConfig()?.takeIf { it.assistantId == assistantId }?.let {
                    json.encodeToString(HeartbeatConfig.serializer(), it.copy(assistantId = assistantId))
                }
            } else {
                null
            }
            ?: return HeartbeatConfig(assistantId = assistantId)
        return runCatching {
            val decoded = json.decodeFromString<HeartbeatConfig>(raw)
            decoded.copy(assistantId = assistantId ?: decoded.assistantId).normalized()
        }.getOrElse { error ->
            recordStateRecovery("config", CONFIG_KEY, error)
            HeartbeatConfig(assistantId = assistantId)
        }
    }

    private fun readRunStatusFromDisk(): HeartbeatRunStatus {
        val storedPhase = preferences.getString(scopedKey(RUN_PHASE_KEY), null)
        val parsedPhase = storedPhase?.let { runCatching { HeartbeatRunPhase.valueOf(it) }.getOrNull() }
        if (storedPhase != null && parsedPhase == null) {
            recordStateRecovery("run_phase", RUN_PHASE_KEY, IllegalArgumentException(storedPhase))
        }
        val storedReason = preferences.getString(scopedKey(RUN_REASON_KEY), null)
        val parsedReason = storedReason?.let { runCatching { HeartbeatRunReason.valueOf(it) }.getOrNull() }
        if (storedReason != null && parsedReason == null) {
            recordStateRecovery("run_reason", RUN_REASON_KEY, IllegalArgumentException(storedReason))
        }
        return HeartbeatRunStatus(
            phase = parsedPhase ?: HeartbeatRunPhase.IDLE,
            reason = parsedReason ?: HeartbeatRunReason.NONE,
            assistantId = preferences.getString(scopedKey(RUN_ASSISTANT_ID_KEY), null),
            detail = preferences.getString(scopedKey(RUN_DETAIL_KEY), null),
            triggerSource = preferences.getString(scopedKey(RUN_TRIGGER_SOURCE_KEY), null),
            startedAtMillis = preferences.getLong(scopedKey(RUN_STARTED_AT_KEY), 0L).takeIf { it > 0L },
            updatedAtMillis = preferences.getLong(scopedKey(RUN_UPDATED_AT_KEY), 0L).takeIf { it > 0L },
            durationMillis = preferences.getLong(scopedKey(RUN_DURATION_KEY), -1L).takeIf { it >= 0L },
        )
    }

    private fun readDiagnosticsFromDisk(): HeartbeatRunDiagnostics {
        val raw = preferences.getString(scopedKey(DIAGNOSTICS_KEY), null)
        val stored = if (raw == null) {
            HeartbeatRunDiagnostics()
        } else {
            runCatching {
                json.decodeFromString<HeartbeatRunDiagnostics>(raw)
                    .copy(schemaVersion = HeartbeatRunDiagnostics.CURRENT_SCHEMA_VERSION)
            }.getOrElse { error ->
                recordStateRecovery("diagnostics", DIAGNOSTICS_KEY, error)
                HeartbeatRunDiagnostics()
            }
        }
        return stored.copy(
            lastStateRecoveryAtMillis = preferences
                .getLong(scopedKey(STATE_RECOVERY_AT_KEY), 0L)
                .takeIf { it > 0L },
            lastStateRecoveryArea = preferences.getString(scopedKey(STATE_RECOVERY_AREA_KEY), null),
        )
    }

    private fun recordStateRecovery(area: String, corruptedKey: String, error: Throwable) {
        preferences.edit()
            .remove(scopedKey(corruptedKey))
            .putLong(scopedKey(STATE_RECOVERY_AT_KEY), System.currentTimeMillis())
            .putString(scopedKey(STATE_RECOVERY_AREA_KEY), area)
            .apply()
        Logging.log(
            tag = HEARTBEAT_LOG_TAG,
            message = "state=recovered area=$area error=${error::class.simpleName}",
        )
    }

    private fun configKey(): String = assistantId?.let { "${CONFIG_KEY}_$it" } ?: CONFIG_KEY

    private fun scopedKey(base: String): String = assistantId?.let { "${base}_$it" } ?: base

    private fun knownAssistantIds(): Set<String> =
        preferences.getStringSet(CONFIG_ASSISTANT_IDS_KEY, emptySet()).orEmpty()

    private fun readLegacyConfig(): HeartbeatConfig? =
        preferences.getString(CONFIG_KEY, null)?.let { raw ->
            runCatching { json.decodeFromString<HeartbeatConfig>(raw).normalized() }.getOrNull()
        }

    companion object {
        private const val PREFERENCES_NAME = "personal_heartbeat"
        private const val CONFIG_KEY = "config"
        private const val CONFIG_ASSISTANT_IDS_KEY = "config_assistant_ids"
        private const val NEXT_TRIGGER_KEY = "next_trigger_at"
        private const val LAST_CLAIM_KEY = "last_claim_at"
        private const val RUN_PHASE_KEY = "run_phase"
        private const val RUN_REASON_KEY = "run_reason"
        private const val RUN_ASSISTANT_ID_KEY = "run_assistant_id"
        private const val RUN_DETAIL_KEY = "run_detail"
        private const val RUN_TRIGGER_SOURCE_KEY = "run_trigger_source"
        private const val RUN_STARTED_AT_KEY = "run_started_at"
        private const val RUN_UPDATED_AT_KEY = "run_updated_at"
        private const val RUN_DURATION_KEY = "run_duration"
        private const val DIAGNOSTICS_KEY = "run_diagnostics_v1"
        private const val STATE_RECOVERY_AT_KEY = "state_recovery_at"
        private const val STATE_RECOVERY_AREA_KEY = "state_recovery_area"
        private const val GOOD_NIGHT_ACTIVE_KEY = "good_night_active"
        private const val GOOD_NIGHT_NO_USAGE_RUNS_KEY = "good_night_no_usage_runs"
        private const val LAST_USER_MESSAGE_AT_KEY = "last_user_message_at"
        private const val LAST_ASSISTANT_MESSAGE_AT_KEY = "last_assistant_message_at"
        private const val DESIRE_STATE_KEY = "desire_state"
    }
}
