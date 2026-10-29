package me.rerere.common.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import okio.buffer
import okio.source
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class RequestTest {
    @Test
    fun `cancelling before response headers cancels HTTP call`() = runBlocking {
        val call = FakeCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.await() }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        call.callback.onFailure(call, IOException("Canceled"))
    }

    @Test
    fun `cancelling while response body is consumed cancels call and closes response`() = runBlocking {
        val call = FakeCall()
        val reading = CountDownLatch(1)
        val bodyClosed = CountDownLatch(1)
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = 0L
            override fun source(): BufferedSource = Buffer()
            override fun close() { bodyClosed.countDown() }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.await {
                reading.countDown()
                check(call.cancelled.await(5, TimeUnit.SECONDS))
                throw IOException("Canceled while reading body")
            }
        }
        val callbackThread = thread {
            call.callback.onResponse(call, Response.Builder().request(call.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())
        }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        callbackThread.join(5000)
        assertTrue(call.isCanceled())
        assertTrue(bodyClosed.await(5, TimeUnit.SECONDS))
        assertFalse(callbackThread.isAlive)
    }

    @Test
    fun `one shot body preserves payload and length while preventing replay`() {
        val original = "{\"test\":true}".toRequestBody()
        val body = original.withoutAutomaticReplay()
        val buffer = Buffer()
        body.writeTo(buffer)
        assertTrue(body.isOneShot())
        assertFalse(original.isOneShot())
        assertEquals(original.contentLength(), body.contentLength())
        assertEquals("{\"test\":true}", buffer.readUtf8())
    }

    @Test
    fun `retry after zero does not silently replay a generation POST`() {
        val server = ServerSocket(0)
        val requests = AtomicInteger()
        val serverThread = thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val input = socket.getInputStream().source().buffer()
                    input.readUtf8LineStrict()
                    var contentLength = 0L
                    while (true) {
                        val line = input.readUtf8LineStrict()
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toLong()
                        }
                    }
                    input.skip(contentLength)
                    requests.incrementAndGet()
                    socket.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\n" +
                        "Content-Length: 0\r\nRetry-After: 0\r\nConnection: close\r\n\r\n").toByteArray())
                }
            } catch (_: IOException) {
                // Closing the test server releases the final accept.
            }
        }
        val client = OkHttpClient()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}")
                .post("test".toRequestBody().withoutAutomaticReplay()).build()
            client.newCall(request).execute().use { assertEquals(503, it.code) }
            assertEquals(1, requests.get())
        } finally {
            server.close()
            serverThread.join(5000)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private class FakeCall : Call by OkHttpClient().newCall(
        Request.Builder().url("https://example.invalid").build(),
    ) {
        lateinit var callback: Callback
        val cancelled = CountDownLatch(1)
        override fun request() = Request.Builder().url("https://example.invalid").build()
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun execute(): Response = error("Asynchronous request expected")
        override fun cancel() { cancelled.countDown() }
        override fun isCanceled() = cancelled.count == 0L
        override fun isExecuted() = this::callback.isInitialized
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall()
    }
}
