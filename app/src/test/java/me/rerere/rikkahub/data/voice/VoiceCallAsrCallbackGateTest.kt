package me.rerere.rikkahub.data.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallAsrCallbackGateTest {
    @Test
    fun closesCallbackGateBeforeStoppingAsr() {
        val gate = VoiceCallAsrCallbackGate()
        gate.startAccepting()
        var stopCalled = false
        var callbackAcceptedDuringStop = true

        gate.stopAsrIfAccepting {
            stopCalled = true
            callbackAcceptedDuringStop = gate.canAcceptCallback()
        }

        assertTrue(stopCalled)
        assertFalse(callbackAcceptedDuringStop)
        assertFalse(gate.canAcceptCallback())
    }

    @Test
    fun doesNotStopAsrAgainAfterGateWasClosed() {
        val gate = VoiceCallAsrCallbackGate()
        gate.stopAccepting()
        var stopCalled = false

        gate.stopAsrIfAccepting { stopCalled = true }

        assertFalse(stopCalled)
        assertFalse(gate.canAcceptCallback())
    }
}
