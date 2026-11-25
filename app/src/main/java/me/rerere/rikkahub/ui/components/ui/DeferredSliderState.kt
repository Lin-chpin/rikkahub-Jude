package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val PENDING_COMMIT_TIMEOUT_MS = 500L

/**
 * Holds transient slider drag state in Compose so continuous dragging stays local at 60fps+
 * and only persists to ViewModel/DataStore when interaction finishes.
 */
@Stable
internal class DeferredSliderState<T>(
    persistedValue: T,
    private val normalizeFloat: (Float) -> T,
    private val toFloat: (T) -> Float,
    private val clampFloat: (Float) -> Float,
) {
    var persistedValue by mutableStateOf(persistedValue)
        private set

    var sliderValue by mutableFloatStateOf(toFloat(persistedValue))
        private set

    var isDragging by mutableStateOf(false)
        private set

    var pendingCommit by mutableStateOf<T?>(null)
        private set

    val displayValue: T
        get() = normalizeFloat(sliderValue)

    fun onValueChange(rawValue: Float) {
        isDragging = true
        sliderValue = clampFloat(rawValue)
    }

    fun onValueChangeFinished(onCommit: (T) -> Unit) {
        val committed = normalizeFloat(sliderValue)
        sliderValue = toFloat(committed)
        isDragging = false
        if (committed != (pendingCommit ?: persistedValue)) {
            pendingCommit = committed
            onCommit(committed)
        }
    }

    fun syncFromPersisted(newPersistedValue: T) {
        persistedValue = newPersistedValue
        val pending = pendingCommit
        if (pending == null) {
            if (!isDragging) {
                sliderValue = toFloat(newPersistedValue)
            }
        } else if (newPersistedValue == pending) {
            pendingCommit = null
            if (!isDragging) {
                sliderValue = toFloat(newPersistedValue)
            }
        }
    }

    fun clearPendingAndSync() {
        pendingCommit = null
        if (!isDragging) {
            sliderValue = toFloat(persistedValue)
        }
    }

    fun cancelDrag(pendingValue: T? = null) {
        isDragging = false
        pendingCommit = pendingValue?.takeIf { it != persistedValue }
        if (pendingValue != null) {
            sliderValue = toFloat(pendingValue)
        }
    }
}

@Composable
internal fun <T> rememberDeferredSliderState(
    key: Any? = Unit,
    persistedValue: T,
    normalizeFloat: (Float) -> T,
    toFloat: (T) -> Float,
    clampFloat: (Float) -> Float,
    onPendingFlush: (T) -> Unit,
): DeferredSliderState<T> {
    val state = remember(key) {
        DeferredSliderState(
            persistedValue = persistedValue,
            normalizeFloat = normalizeFloat,
            toFloat = toFloat,
            clampFloat = clampFloat,
        )
    }
    val latestOnPendingFlush by rememberUpdatedState(onPendingFlush)
    var activeKey by remember { mutableStateOf(key) }
    SideEffect {
        activeKey = key
    }

    LaunchedEffect(state, persistedValue) {
        state.syncFromPersisted(persistedValue)
    }

    LaunchedEffect(state, state.pendingCommit) {
        if (state.pendingCommit != null) {
            delay(PENDING_COMMIT_TIMEOUT_MS)
            state.clearPendingAndSync()
        }
    }

    DisposableEffect(state) {
        onDispose {
            if (state.isDragging && activeKey == key) {
                state.onValueChangeFinished(latestOnPendingFlush)
            }
        }
    }

    return state
}
