package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.ui.components.ui.DeferredSliderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class AssistantBasicSliderStateTest {
    private fun newContextState(initial: Int = 80): DeferredSliderState<Int> =
        DeferredSliderState(
            persistedValue = clampContextMessageSize(initial),
            normalizeFloat = ::normalizeContextMessageSliderValue,
            toFloat = Int::toFloat,
            clampFloat = { value ->
                if (value.isNaN()) {
                    DEFAULT_CONTEXT_MESSAGE_SIZE.toFloat()
                } else {
                    value.coerceIn(CONTEXT_MESSAGE_MIN.toFloat(), CONTEXT_MESSAGE_MAX.toFloat())
                }
            },
        )

    @Test
    fun clampAndNormalizeContextMessageValuesHandleBoundsAndNaN() {
        assertEquals(1, clampContextMessageSize(0))
        assertEquals(1, clampContextMessageSize(-20))
        assertEquals(80, clampContextMessageSize(80))
        assertEquals(512, clampContextMessageSize(512))
        assertEquals(512, clampContextMessageSize(999))

        assertEquals(1, normalizeContextMessageSliderValue(0.1f))
        assertEquals(80, normalizeContextMessageSliderValue(80.4f))
        assertEquals(81, normalizeContextMessageSliderValue(80.6f))
        assertEquals(512, normalizeContextMessageSliderValue(600f))
        assertEquals(DEFAULT_CONTEXT_MESSAGE_SIZE, normalizeContextMessageSliderValue(Float.NaN))
    }

    @Test
    fun normalizeOpacityClampsRoundsAndHandlesNaN() {
        assertEquals(0f, normalizeOpacity(-0.5f), 0.0001f)
        assertEquals(0.35f, normalizeOpacity(0.35000002f), 0.0001f)
        assertEquals(1f, normalizeOpacity(1.4f), 0.0001f)
        assertEquals(1f, normalizeOpacity(Float.NaN), 0.0001f)
    }

    @Test
    fun dragUpdatesLocallyAndCommitsOnceOnFinish() {
        val state = newContextState(80)
        val commits = mutableListOf<Int>()

        state.onValueChange(100.3f)
        state.onValueChange(150.7f)
        state.onValueChange(200.6f)

        assertTrue(state.isDragging)
        assertEquals(201, state.displayValue)
        assertTrue(commits.isEmpty())

        state.onValueChangeFinished(commits::add)

        assertFalse(state.isDragging)
        assertEquals(201f, state.sliderValue, 0.0001f)
        assertEquals(listOf(201), commits)
        assertEquals(201, state.pendingCommit)

        // Same value without change does not re-commit
        state.syncFromPersisted(201)
        assertNull(state.pendingCommit)
        state.onValueChangeFinished(commits::add)
        assertEquals(listOf(201), commits)
    }

    @Test
    fun externalPersistedUpdatesAreIgnoredWhileDraggingAndAppliedWhenIdle() {
        val state = newContextState(80)

        state.syncFromPersisted(120)
        assertEquals(120, state.displayValue)

        state.onValueChange(250f)
        state.syncFromPersisted(40)
        assertEquals(250, state.displayValue)
        assertEquals(40, state.persistedValue)

        val commits = mutableListOf<Int>()
        state.onValueChangeFinished(commits::add)
        assertEquals(listOf(250), commits)
    }

    @Test
    fun rapidBackAndForthDragDoesNotDropReturnToOriginalValue() {
        val state = newContextState(80)
        val commits = mutableListOf<Int>()

        // First drag: 80 -> 120 (commit in flight, persistedValue still 80)
        state.onValueChange(120f)
        state.onValueChangeFinished(commits::add)
        assertEquals(listOf(120), commits)

        // Second drag back to 80 before first commit lands
        state.onValueChange(80f)
        state.onValueChangeFinished(commits::add)
        assertEquals(listOf(120, 80), commits)

        // Stale 120 emission arrives first: slider must stay at 80
        state.syncFromPersisted(120)
        assertEquals(80, state.displayValue)
        assertEquals(80, state.pendingCommit)

        // Final 80 emission arrives: pending clears
        state.syncFromPersisted(80)
        assertEquals(80, state.displayValue)
        assertNull(state.pendingCommit)
    }

    @Test
    fun clearPendingAndSyncRecoversWhenCommitDoesNotArrive() {
        val state = newContextState(80)
        state.onValueChange(160f)
        state.onValueChangeFinished { }
        assertEquals(160, state.pendingCommit)

        state.clearPendingAndSync()
        assertNull(state.pendingCommit)
        assertEquals(80, state.displayValue)
    }

    @Test
    fun resolveContextMessageLimitTogglePreservesTransientAndPendingValues() {
        assertEquals(
            80,
            resolveContextMessageLimitToggle(
                enabled = true,
                persistedSize = 0,
                transientSliderValue = null,
                pendingCommittedSize = null,
            )
        )
        assertEquals(
            512,
            resolveContextMessageLimitToggle(
                enabled = true,
                persistedSize = 900,
                transientSliderValue = null,
                pendingCommittedSize = null,
            )
        )
        assertEquals(
            0,
            resolveContextMessageLimitToggle(
                enabled = false,
                persistedSize = 0,
                transientSliderValue = null,
                pendingCommittedSize = null,
            )
        )
        assertEquals(
            240,
            resolveContextMessageLimitToggle(
                enabled = false,
                persistedSize = 80,
                transientSliderValue = 240,
                pendingCommittedSize = 150,
            )
        )
        assertEquals(
            150,
            resolveContextMessageLimitToggle(
                enabled = false,
                persistedSize = 80,
                transientSliderValue = null,
                pendingCommittedSize = 150,
            )
        )
    }

    @Test
    fun cancelDragTracksPendingOnlyWhenDifferentFromPersisted() {
        val state = newContextState(80)
        state.onValueChange(140f)
        state.cancelDrag(pendingValue = 80)
        assertFalse(state.isDragging)
        assertNull(state.pendingCommit)
        assertEquals(80, state.displayValue)

        state.onValueChange(140f)
        state.cancelDrag(pendingValue = 140)
        assertFalse(state.isDragging)
        assertEquals(140, state.pendingCommit)
        assertEquals(140, state.displayValue)
    }

    @Test
    fun deferredSliderStateSupportsFloatRangesAndThresholdBounds() {
        val fontSizeState = DeferredSliderState(
            persistedValue = 1.0f,
            normalizeFloat = { value ->
                if (value.isNaN()) 1.0f else (value.coerceIn(0.5f, 2f) * 1000f).roundToInt() / 1000f
            },
            toFloat = { it },
            clampFloat = { value ->
                if (value.isNaN()) 1.0f else value.coerceIn(0.5f, 2f)
            },
        )
        val commits = mutableListOf<Float>()

        fontSizeState.onValueChange(0.6254f)
        fontSizeState.syncFromPersisted(1.25f)
        assertTrue(fontSizeState.isDragging)
        assertEquals(0.625f, fontSizeState.displayValue, 0.0001f)
        assertTrue(commits.isEmpty())

        fontSizeState.onValueChangeFinished(commits::add)
        assertFalse(fontSizeState.isDragging)
        assertEquals(listOf(0.625f), commits)

        val thresholdState = DeferredSliderState(
            persistedValue = 1000,
            normalizeFloat = { value ->
                if (value.isNaN()) 100 else value.roundToInt().coerceIn(100, 10000)
            },
            toFloat = Int::toFloat,
            clampFloat = { value ->
                if (value.isNaN()) 100f else value.coerceIn(100f, 10000f)
            },
        )
        thresholdState.onValueChange(50f)
        assertEquals(100, thresholdState.displayValue)
        thresholdState.onValueChange(12000f)
        assertEquals(10000, thresholdState.displayValue)
    }
}
