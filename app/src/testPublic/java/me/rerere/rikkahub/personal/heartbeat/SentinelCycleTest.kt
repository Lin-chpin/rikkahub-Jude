package me.rerere.rikkahub.personal.heartbeat

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class SentinelCycleTest {
    @Test fun observationsKeepShortCycleUntilModelSkips() {
        val config = HeartbeatConfig(enabled = true, sentinel = SentinelConfig(enabled = true))
        var state = SentinelState()
        assertEquals(60, config.forCurrentCycle(state).minIntervalMinutes)
        repeat(4) { index ->
            assertTrue(state.canObserve(100L + index))
            state = Json.decodeSentinelState(Json.encodeToString(state.enter(100L + index)))
            assertEquals(index + 1, state.consecutiveRounds)
            assertEquals(5, config.forCurrentCycle(state).minIntervalMinutes)
        }
        assertTrue(state.canObserve(200))
        val skipped = state.exit(200).copy(ordinaryNotBeforeMillis = 500)
        assertEquals(60, config.forCurrentCycle(skipped).minIntervalMinutes)
        assertFalse(skipped.canObserve(499))
        assertTrue(skipped.canObserve(500))
        assertEquals(1, skipped.enter(500).consecutiveRounds)
    }

    @Test fun userActivityInvalidatesResultsWithoutResettingRoundCount() {
        val observed = SentinelState().enter(100).enter(200)
        val userReturned = observed.invalidate()
        assertEquals(2, userReturned.consecutiveRounds)
        assertNotEquals(observed.revision, userReturned.revision)
        assertEquals(310_000, HeartbeatScheduleTiming.nextRegularTriggerAtMillis(10_000, 5, 10_000, true))
        assertEquals(320_000, HeartbeatScheduleTiming.nextRegularTriggerAtMillis(20_000, 5, 20_000, true))
    }

    @Test fun repeatedCallsReuseEvenAFailureWithoutAnotherScreenshot() = runBlocking {
        val observation = SentinelWakeObservation()
        var screenshots = 0
        val failure = listOf<UIMessagePart>(UIMessagePart.Text("device_asleep"))
        val outputs = (1..8).map { async { observation.get { screenshots++; failure } } }.awaitAll()
        assertEquals(1, screenshots)
        assertTrue(outputs.all { it === failure })
    }

    @Test fun pauseAndDisableRestoreOrdinaryParameters() {
        val config = HeartbeatConfig(enabled = true, sentinel = SentinelConfig(enabled = true))
        val paused = SentinelState().enter(10).exit(20, "accessibility_disconnected")
        assertEquals(60, config.forCurrentCycle(paused).minIntervalMinutes)
        assertFalse(paused.canObserve(50))
        assertFalse(config.copy(enabled = false).normalized().sentinel.enabled)
    }

    @Test fun persistedInvalidCountersAreStillRejectedAndPauseStateSurvives() {
        listOf(SentinelState(consecutiveRounds = -1), SentinelState(active = true)).forEach { invalid ->
            assertTrue(runCatching { Json.decodeSentinelState(Json.encodeToString(invalid)) }.isFailure)
        }
        val paused = SentinelState(revision = 9, pauseReason = "accessibility_disconnected")
        assertEquals(paused, Json.decodeSentinelState(Json.encodeToString(paused)))
    }
}
