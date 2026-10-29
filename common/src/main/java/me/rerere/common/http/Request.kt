package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.BufferedSink
import okio.IOException
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}

/** Keeps cancellation bound to the HTTP call until the response body has been consumed. */
suspend fun <T> Call.await(consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.closeQuietly()
                return
            }
            try {
                val result = response.use(consume)
                if (continuation.isActive) continuation.resumeWith(Result.success(result))
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

/** Prevents OkHttp from replaying a generation POST after its body has been sent. */
fun RequestBody.withoutAutomaticReplay(): RequestBody {
    val body = this
    return object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }
}
