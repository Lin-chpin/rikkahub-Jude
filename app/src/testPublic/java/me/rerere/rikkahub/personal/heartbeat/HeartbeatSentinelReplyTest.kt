package me.rerere.rikkahub.personal.heartbeat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class HeartbeatSentinelReplyTest {
    private val screenshot = UIMessagePart.Image("file:///images/sentinel-test.jpg")
    private val observed = UIMessagePart.Tool(
        toolCallId = "actual-call-id",
        toolName = "sentinel_observe",
        input = """{"purpose":"supervision"}""",
        output = listOf(UIMessagePart.Text(
            """{"status":"observed","screenshot_captured":true,"vision_completed":true,"observation":"screen result"}""",
        )),
    )
    private fun messages(tool: UIMessagePart.Tool = observed) = listOf(
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)),
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("unfinished reply"))),
    )

    @Test fun rateLimitAfterObservationPreservesActualToolAndScreenshot() = runBlocking {
        val failure = Exception("429 secret-response-body")
        var delivered: UIMessage? = null
        val thrown = runCatching {
            preserveSentinelReplyOnFailure(messages(), screenshot, failure) { delivered = it }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        val receipt = requireNotNull(delivered)
        assertNotNull(receipt.finishedAt)
        assertEquals(listOf(observed), receipt.parts.filterIsInstance<UIMessagePart.Tool>())
        assertEquals(listOf(screenshot), receipt.parts.filterIsInstance<UIMessagePart.Image>())
        assertTrue(receipt.toText().contains("系统回执"))
        assertTrue(receipt.toText().contains("截图：成功"))
        assertTrue(receipt.toText().contains("识图：成功"))
        assertTrue(receipt.toText().contains("回复模型服务限流"))
        assertFalse(receipt.toText().contains("unfinished reply"))
        assertFalse(receipt.toText().contains("secret-response-body"))
    }

    @Test fun missingOrUnexecutedSentinelDoesNotProduceAReceipt() {
        assertNull(sentinelFailureReply(emptyList(), null, Exception("429")))
        assertNull(sentinelFailureReply(messages(observed.copy(output = emptyList())), screenshot, Exception("429")))
        assertNull(sentinelFailureReply(messages(observed.copy(toolName = "other_tool")), screenshot, Exception("429")))
    }

    @Test fun failedVisionKeepsScreenshotWithoutClaimingRecognitionSucceeded() {
        val failedVision = observed.copy(output = listOf(UIMessagePart.Text(
            """{"status":"vision_request_failed","screenshot_captured":true,"vision_completed":false}""",
        )))
        val receipt = requireNotNull(sentinelFailureReply(messages(failedVision), screenshot, Exception("429")))
        assertTrue(receipt.toText().contains("截图：成功"))
        assertTrue(receipt.toText().contains("识图：未完成（vision_request_failed）"))
        assertEquals(listOf(screenshot), receipt.parts.filterIsInstance<UIMessagePart.Image>())
    }

    @Test fun userCancellationDoesNotDeliverAndStorageFailureDoesNotMaskProviderFailure() = runBlocking {
        val cancelled = CancellationException("obsolete observation")
        var deliveries = 0
        assertSame(cancelled, runCatching {
            preserveSentinelReplyOnFailure(messages(), screenshot, cancelled) { deliveries++ }
        }.exceptionOrNull())
        assertEquals(0, deliveries)

        val providerFailure = Exception("429")
        val storageFailure = Exception("disk full")
        assertSame(providerFailure, runCatching {
            preserveSentinelReplyOnFailure(messages(), screenshot, providerFailure) { throw storageFailure }
        }.exceptionOrNull())
        val suppressed = providerFailure.suppressed.single()
        assertEquals(storageFailure.javaClass, suppressed.javaClass)
        assertEquals(storageFailure.message, suppressed.message)
    }

    @Test fun expiredGenerationTimeoutStillDeliversCompletedEvidence() = runBlocking {
        var delivered: UIMessage? = null
        val thrown = runCatching {
            withTimeout(100L) {
                try {
                    awaitCancellation()
                } catch (error: TimeoutCancellationException) {
                    preserveSentinelReplyOnFailure(messages(), screenshot, error) {
                        currentCoroutineContext().ensureActive()
                        delivered = it
                    }
                }
            }
        }.exceptionOrNull()
        assertTrue(thrown is TimeoutCancellationException)
        assertEquals(listOf(observed, screenshot), requireNotNull(delivered).parts.drop(1))
    }
}
