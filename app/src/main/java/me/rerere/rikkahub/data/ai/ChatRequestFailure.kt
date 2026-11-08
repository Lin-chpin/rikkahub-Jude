package me.rerere.rikkahub.data.ai

import me.rerere.common.http.privateFailureStatusCode
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderResponseParseException
import me.rerere.rikkahub.data.ai.transformers.OcrFailureException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun Throwable.chatErrorExplanation(): String {
    val causes = causeChain()
    val providerFailure = causes.filterIsInstance<HttpException>()
        .firstNotNullOfOrNull { it.chatProviderErrorExplanation() }
    val status = causes.filterIsInstance<HttpException>().firstNotNullOfOrNull { it.statusCode }
        ?: privateFailureStatusCode(this)
    return when {
        this is EmptyAssistantResponseException -> message.orEmpty()
        causes.filterIsInstance<OcrFailureException>().firstOrNull() != null ->
            causes.filterIsInstance<OcrFailureException>().first().userMessage()
        providerFailure != null -> providerFailure
        status == 400 -> "接口拒绝了本次请求（HTTP 400），请查看接口错误详情。"
        status == 401 -> "接口身份验证失败，请检查 API 密钥或账号登录状态。"
        status == 403 -> "接口拒绝了访问（HTTP 403），请检查账号及模型权限。"
        status == 404 -> "接口地址或请求资源未找到（HTTP 404），请检查服务地址和模型设置。"
        status == 408 -> "服务端报告请求超时（HTTP 408），你可以稍后手动重试。"
        status == 413 -> "本次请求过大，请减少上下文或附件后重试。"
        status == 429 -> "请求受到接口限流或额度限制（HTTP 429），请检查账号用量或稍后重试。"
        status != null && status in 500..599 -> "服务端返回 HTTP $status 错误，具体内部原因无法从状态码确认。"
        status != null -> "接口返回 HTTP $status，具体原因无法从状态码确认。"
        causes.any { it is SocketTimeoutException } -> "请求超时；无法仅据此确认超时发生在哪个网络阶段。"
        causes.any { it is UnknownHostException } -> "无法解析接口域名，请检查接口地址和网络连接。"
        causes.any { it is SSLException } -> "无法建立安全连接，请检查接口证书和网络环境。"
        causes.any { it is IOException } -> "请求 I/O 阶段失败；具体原因无法确认。"
        causes.any { it is ProviderResponseParseException } -> "模型响应解析失败；上游返回的数据不符合可识别格式。"
        causes.any { it is HttpException } -> "接口报告请求失败，具体原因无法确认。"
        else -> "本次操作未能完成，具体原因无法确认。"
    }
}

private fun HttpException.chatProviderErrorExplanation(): String? {
    val code = providerCode.orEmpty().lowercase()
    val type = providerType.orEmpty().lowercase()
    return when {
        code in setOf("safety", "prohibited_content", "content_policy_violation", "blocklist", "spii", "image_safety", "model_armor") ||
            type in setOf("safety", "prohibited_content", "content_policy_violation", "blocklist", "spii", "image_safety", "model_armor") ->
            "上游模型按其内容策略拒绝了请求。"
        code in setOf("invalid_api_key", "authentication_error", "auth_error", "unauthenticated", "missing_api_key", "invalid_grant", "unauthorized") ||
            type in setOf("authentication_error", "auth_error", "unauthenticated") -> "接口身份验证失败，请检查 API 密钥或账号登录状态。"
        code in setOf("rate_limit_exceeded", "rate_limited", "too_many_requests", "rate_limit_error") ||
            type in setOf("rate_limit_error", "rate_limit_exceeded", "rate_limited") -> "请求受到接口限流，请稍后重试并检查账号用量。"
        code in setOf("insufficient_quota", "quota_exceeded", "payment_issue", "billing_hard_limit_reached") ->
            "接口报告账户额度或付款状态不可用，请检查账号用量与账单。"
        code in setOf("invalid_request_error", "invalid_request", "bad_request", "invalid_argument", "invalid_parameter", "unsupported_parameter") ||
            type in setOf("invalid_request_error", "invalid_request", "bad_request") -> "接口拒绝了请求参数，请检查模型参数和自定义请求体。"
        code in setOf("context_length_exceeded", "payload_too_large", "request_too_large") ||
            type in setOf("context_length_exceeded", "payload_too_large", "request_too_large") ->
            "请求内容超过了模型或服务限制，请减少上下文或附件后重试。"
        code in setOf("model_not_found", "unknown_model", "not_found", "not_found_error") -> "接口未找到所选模型或请求资源，请检查模型配置。"
        code in setOf("permission_denied", "access_denied", "forbidden") || type in setOf("permission_error", "permission_denied", "access_denied", "forbidden") ->
            "接口拒绝了访问，请检查账号及模型权限。"
        code in setOf("server_error", "api_error", "internal_error", "unavailable", "overloaded_error") ||
            type in setOf("server_error", "api_error", "internal_error", "unavailable", "overloaded_error") ->
            "接口报告服务端错误，具体内部原因无法确认。"
        else -> null
    }
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this@causeChain
    while (current != null && visited.add(current)) {
        yield(current)
        current = current.cause
    }
}
