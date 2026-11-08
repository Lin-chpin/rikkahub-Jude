package me.rerere.rikkahub.personal.heartbeat

import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

class HeartbeatFailureClassifierTest {
    @Test
    fun `HTTP status remains the source of provider failure classification`() {
        assertEquals(HeartbeatRunReason.AUTHENTICATION_FAILED, HeartbeatFailureClassifier.classify(HttpException("Unauthorized", 401)))
        assertEquals(HeartbeatRunReason.ACCESS_DENIED, HeartbeatFailureClassifier.classify(HttpException("Forbidden", 403)))
        assertEquals(HeartbeatRunReason.RATE_LIMITED, HeartbeatFailureClassifier.classify(HttpException("Too many requests", 429)))
        assertEquals(HeartbeatRunReason.REQUEST_REJECTED, HeartbeatFailureClassifier.classify(HttpException("Bad Request", 400)))
        assertEquals(HeartbeatRunReason.PAYMENT_REQUIRED, HeartbeatFailureClassifier.classify(HttpException("Payment Required", 402)))
        assertEquals(HeartbeatRunReason.RESOURCE_NOT_FOUND, HeartbeatFailureClassifier.classify(HttpException("Not Found", 404)))
        assertEquals(HeartbeatRunReason.SERVICE_REQUEST_TIMEOUT, HeartbeatFailureClassifier.classify(HttpException("Request Timeout", 408)))
        assertEquals(HeartbeatRunReason.REQUEST_TOO_LARGE, HeartbeatFailureClassifier.classify(HttpException("Payload Too Large", 413)))
        assertEquals(HeartbeatRunReason.SERVER_ERROR, HeartbeatFailureClassifier.classify(HttpException("Internal Server Error", 503)))
        assertEquals(HeartbeatRunReason.HTTP_ERROR, HeartbeatFailureClassifier.classify(HttpException("unprocessable", 422)))
        assertEquals(503, HeartbeatFailureClassifier.httpStatusCode(HttpException("Internal Server Error", 503)))
        assertEquals(
            HeartbeatRunReason.RATE_LIMITED,
            HeartbeatFailureClassifier.classify(HttpException("opaque", providerCode = "rate_limit_exceeded")),
        )
        assertEquals(
            HeartbeatRunReason.AUTHENTICATION_FAILED,
            HeartbeatFailureClassifier.classify(HttpException("opaque", providerType = "auth_error")),
        )
        assertEquals(
            HeartbeatRunReason.ACCESS_DENIED,
            HeartbeatFailureClassifier.classify(HttpException("opaque", providerCode = "permission_denied")),
        )
        assertEquals(
            HeartbeatRunReason.REQUEST_REJECTED,
            HeartbeatFailureClassifier.classify(HttpException("opaque", providerCode = "bad_request")),
        )
        assertEquals(
            HeartbeatRunReason.CONTENT_POLICY_REJECTED,
            HeartbeatFailureClassifier.classify(HttpException("opaque", providerCode = "content_policy_violation")),
        )
        assertEquals(
            HeartbeatRunReason.REQUEST_TOO_LARGE,
            HeartbeatFailureClassifier.classify(HttpException("opaque", providerCode = "context_length_exceeded")),
        )
        assertEquals(
            HeartbeatRunReason.SERVER_ERROR,
            HeartbeatFailureClassifier.classify(HttpException("opaque", providerType = "api_error")),
        )
    }

    @Test
    fun `unrelated numbers and local IO do not imply authentication or network failure`() {
        assertEquals(
            HeartbeatRunReason.GENERATION_FAILURE,
            HeartbeatFailureClassifier.classify(IOException("read 1401 bytes")),
        )
        assertEquals(HeartbeatRunReason.GENERATION_FAILURE, HeartbeatFailureClassifier.classify(IOException("unauthorized rate limit")))
        assertEquals(HeartbeatRunReason.HTTP_ERROR, HeartbeatFailureClassifier.classify(
            HttpException("unprocessable", 422, cause = SocketTimeoutException("read timeout")),
        ))
    }

    @Test
    fun `transport causes preserve only their known network stage`() {
        assertEquals(
            HeartbeatRunReason.NETWORK_TIMEOUT,
            HeartbeatFailureClassifier.classify(IOException("request", SocketTimeoutException("timed out"))),
        )
        assertEquals(
            HeartbeatRunReason.NETWORK_UNAVAILABLE,
            HeartbeatFailureClassifier.classify(UnknownHostException("api.example")),
        )
        assertEquals(
            HeartbeatRunReason.NETWORK_TRANSPORT_FAILURE,
            HeartbeatFailureClassifier.classify(SocketException("connection reset")),
        )
        assertEquals(
            HeartbeatRunReason.SECURE_CONNECTION_FAILURE,
            HeartbeatFailureClassifier.classify(SSLException("certificate rejected")),
        )
        assertEquals(
            HeartbeatRunReason.GENERATION_FAILURE,
            HeartbeatFailureClassifier.classify(IllegalStateException("operation timeout")),
        )
        assertEquals(HeartbeatRunReason.REQUEST_TIMEOUT, HeartbeatFailureClassifier.classify(TimeoutException()))
    }
}
