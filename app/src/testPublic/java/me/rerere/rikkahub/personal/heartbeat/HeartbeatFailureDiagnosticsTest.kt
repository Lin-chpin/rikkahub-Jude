package me.rerere.rikkahub.personal.heartbeat

import me.rerere.common.http.privateRequestFailure
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class HeartbeatFailureDiagnosticsTest {
    @Test fun stagesBelongToCurrentWakeAndDistinguishObservationFromMainFollowup() {
        val diagnostics = SentinelDiagnostics().begin(100L, "manual")
        assertEquals("main_decision", diagnostics.generationFailureStage(100L))
        val observing = diagnostics.copy(phase = "observing", toolCalled = true)
        assertEquals("observation", observing.generationFailureStage(100L))
        val observed = observing.complete("observed", "observed", 200L, true)
        assertEquals("main_followup", observed.generationFailureStage(100L))
        assertEquals("main_decision", observed.generationFailureStage(300L))
    }

    @Test fun quotaCountersAndRetrySecondsAreNotMisclassifiedAsHttpStatuses() {
        listOf(429 to HeartbeatRunReason.RATE_LIMITED, 500 to HeartbeatRunReason.GENERATION_FAILURE).forEach { (status, reason) ->
            Response.Builder().request(Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1).code(status).message("error")
                .header("Retry-After", "401").header("x-ratelimit-limit-tokens", "42900")
                .body("""{"error":{"message":"secret"}}""".toResponseBody()).build().use {
                    val error = privateRequestFailure(null, status, it)
                    assertEquals(reason, HeartbeatFailureClassifier.classify(error))
                    val detail = error.toPrivateHeartbeatDiagnosticDetail("main_followup")
                    assertTrue(detail.contains("stage=main_followup http_status=$status"))
                    assertTrue(detail.contains("retry_after_seconds=401.0"))
                    assertFalse(detail.contains("secret"))
                }
        }
    }
}
