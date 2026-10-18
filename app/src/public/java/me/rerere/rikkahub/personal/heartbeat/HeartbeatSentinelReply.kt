package me.rerere.rikkahub.personal.heartbeat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal suspend fun preserveSentinelReplyOnFailure(
    messages: List<UIMessage>,
    screenshot: UIMessagePart.Image?,
    error: Throwable,
    deliver: suspend (UIMessage) -> Unit,
): Nothing {
    if (error is CancellationException && error !is TimeoutCancellationException) throw error
    if (error !is Exception) throw error
    val receipt = sentinelFailureReply(messages, screenshot, error)
    if (receipt != null) {
        try {
            // An expired generation timeout must not discard completed tools. Delivery still
            // checks the current assistant, permissions and user activity, and is time bounded.
            withContext(NonCancellable) {
                withTimeout(5_000L) { deliver(receipt) }
            }
        } catch (deliveryError: Exception) {
            error.addSuppressed(deliveryError)
        }
    }
    // Keep failure diagnostics and backoff even when the execution receipt was delivered.
    throw error
}

internal fun sentinelReplyEvidence(
    messages: List<UIMessage>,
    screenshot: UIMessagePart.Image?,
): List<UIMessagePart> = buildList {
    messages.filter { it.role == MessageRole.ASSISTANT }
        .flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Tool>()
        .filter { it.toolName == SentinelRun.NAME && it.isExecuted }
        .forEach { add(it) }
    screenshot?.let { add(it) }
}

/** A completed observation survives failure of the subsequent model request. */
internal fun sentinelFailureReply(
    messages: List<UIMessage>,
    screenshot: UIMessagePart.Image?,
    error: Throwable,
): UIMessage? {
    val evidence = sentinelReplyEvidence(messages, screenshot)
    val tool = evidence.filterIsInstance<UIMessagePart.Tool>().lastOrNull() ?: return null
    val result = tool.output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull {
        runCatching { Json.parseToJsonElement(it.text).jsonObject }.getOrNull()
    }
    val status = result?.get("status")?.jsonPrimitive?.contentOrNull ?: "结果见工具卡片"
    val captured = result?.get("screenshot_captured")?.jsonPrimitive?.booleanOrNull == true
    val recognized = result?.get("vision_completed")?.jsonPrimitive?.booleanOrNull == true
    val reason = when (HeartbeatFailureClassifier.classify(error)) {
        HeartbeatRunReason.RATE_LIMITED -> "回复模型服务限流（请求过于频繁）"
        HeartbeatRunReason.AUTHENTICATION_FAILED -> "回复模型服务鉴权失败"
        HeartbeatRunReason.NETWORK_TIMEOUT -> "回复模型请求超时"
        HeartbeatRunReason.NETWORK_UNAVAILABLE -> "回复模型网络连接失败"
        else -> "回复生成失败"
    }
    val receipt = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(buildString {
            appendLine("哨兵执行记录（系统回执）")
            appendLine("截图：${if (captured) "成功" else "未完成（$status）"}。")
            if (captured && screenshot == null) appendLine("截图附件未能保存。")
            appendLine("识图：${if (recognized) "成功" else "未完成（$status）"}。")
            append("后续回复：$reason，未取得完整的 AI 回复。实际工具结果见随附卡片。")
        })) + evidence,
    )
    return receipt.copy(finishedAt = receipt.createdAt)
}
