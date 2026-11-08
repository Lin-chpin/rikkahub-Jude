package me.rerere.asr.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolcengineASRFrameBoundsTest {
    @Test
    fun payloadLengthValidationDoesNotOverflow() {
        assertTrue(framePayloadFits(dataSize = 12, offset = 8, payloadSize = 4))
        assertFalse(framePayloadFits(dataSize = 12, offset = 8, payloadSize = 5))
        assertFalse(framePayloadFits(dataSize = 12, offset = 8, payloadSize = Int.MAX_VALUE))
        assertFalse(framePayloadFits(dataSize = 12, offset = 8, payloadSize = -1))
        assertFalse(framePayloadFits(dataSize = 12, offset = Int.MAX_VALUE, payloadSize = 4))
    }
}
