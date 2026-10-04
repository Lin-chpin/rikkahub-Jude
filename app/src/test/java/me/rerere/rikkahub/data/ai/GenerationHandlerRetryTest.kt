package me.rerere.rikkahub.data.ai

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerRetryTest {
    @Test
    fun retriesOnlyIoFailuresWithinTheLimit() {
        assertTrue(isRetryableProviderStreamFailure(IOException("connection aborted"), 0))
        assertTrue(isRetryableProviderStreamFailure(IOException("connection aborted"), 2))
        assertFalse(isRetryableProviderStreamFailure(IOException("connection aborted"), 3))
        assertFalse(isRetryableProviderStreamFailure(IllegalStateException("bad stream"), 0))
    }

    @Test
    fun retryDelayUsesExponentialBackoff() {
        assertEquals(1_000L, providerStreamRetryDelayMillis(0))
        assertEquals(2_000L, providerStreamRetryDelayMillis(1))
        assertEquals(4_000L, providerStreamRetryDelayMillis(2))
    }
}
