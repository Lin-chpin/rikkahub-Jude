package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class McpHttpClientProviderTest {
    @Test
    fun clientIsCreatedLazilyOnceOnIoAndSharedByConcurrentCallers() = runBlocking {
        val creations = AtomicInteger()
        val creationThread = AtomicReference<Thread?>()
        val callbackThread = AtomicReference<Thread?>()
        val client = Any()
        val provider = McpHttpClientProvider {
            creations.incrementAndGet()
            creationThread.set(Thread.currentThread())
            client
        }

        assertEquals(0, creations.get())
        val callerThread = Thread.currentThread()
        val clients = coroutineScope {
            List(8) {
                async {
                    provider.withClient {
                        callbackThread.set(Thread.currentThread())
                        it
                    }
                }
            }.awaitAll()
        }

        clients.forEach { assertSame(client, it) }
        assertSame(client, provider.withClient { it })
        assertEquals(1, creations.get())
        assertNotSame(callerThread, creationThread.get())
        assertNotSame(callerThread, callbackThread.get())
    }
}
