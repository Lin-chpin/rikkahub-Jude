package me.rerere.ai.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SSEEventSourceTest {
    @Test
    fun `missing content type is allowed only when explicitly enabled`() {
        val request = Request.Builder().url("https://chatgpt.com/backend-api/codex/responses").build()
        var receivedData: String? = null
        var failure: Throwable? = null
        var closed = false
        val eventSource = SSEEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    receivedData = data
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    failure = t
                }

                override fun onClosed(eventSource: EventSource) {
                    closed = true
                }
            },
            allowMissingContentType = true,
        )

        eventSource.processResponse(
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("data: {\"type\":\"response.completed\"}\n\n".toResponseBody(null))
                .build()
        )

        assertEquals("{\"type\":\"response.completed\"}", receivedData)
        assertNull(failure)
        assertEquals(true, closed)
    }

    @Test
    fun `incorrect content type is still rejected`() {
        val request = Request.Builder().url("https://chatgpt.com/backend-api/codex/responses").build()
        var failure: Throwable? = null
        val eventSource = SSEEventSource(
            request,
            object : EventSourceListener() {
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    failure = t
                }
            },
            allowMissingContentType = true,
        )

        eventSource.processResponse(
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        )

        assertEquals("Invalid content-type: application/json; charset=utf-8", failure?.message)
    }
}
