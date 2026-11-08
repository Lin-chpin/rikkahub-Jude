package me.rerere.asr

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal fun realtimeAsrWebSocketClosedMessage(provider: String, code: Int): String =
    "$provider 实时连接已关闭（WebSocket $code），具体原因无法确认。"

internal fun isExpectedAsrWebSocketClosure(userStopped: Boolean, code: Int): Boolean =
    userStopped && code == 1000

internal fun realtimeAsrErrorMessage(
    provider: String,
    httpStatus: Int? = null,
    providerCode: String? = null,
    transportError: Throwable? = null,
    responseParsingFailed: Boolean = false,
    providerReportedFailure: Boolean = false,
): String {
    val causes = transportError?.causeChain()?.toList().orEmpty()
    val code = providerCode?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9_.-]{1,64}")) }
    when (code) {
        "invalid_api_key", "authentication_error", "auth_error", "unauthorized" ->
            return "$provider 身份验证失败，请检查 API Key 或账号登录状态。"
        "payment_issue", "payment_required", "insufficient_quota", "quota_exceeded" ->
            return "$provider 要求检查账户付款状态或可用额度。"
        "rate_limit_exceeded", "rate_limited", "too_many_requests" ->
            return "$provider 限制了请求频率，请稍后重试。"
        "permission_denied", "forbidden" -> return "$provider 拒绝访问，请检查账号权限。"
        "model_not_found", "resource_not_found", "not_found" -> return "$provider 未找到请求的资源。"
        "invalid_request", "invalid_request_error", "invalid_parameter", "bad_request" ->
            return "$provider 拒绝了请求，请检查语音识别配置。"
    }
    when (httpStatus) {
        400 -> return "$provider 拒绝了请求（HTTP 400），请检查语音识别配置。"
        401 -> return "$provider 身份验证失败（HTTP 401），请检查 API Key 或账号登录状态。"
        402 -> return "$provider 要求检查账户付款状态或可用额度（HTTP 402）。"
        403 -> return "$provider 拒绝访问（HTTP 403），请检查账号权限。"
        404 -> return "$provider 未找到请求的资源（HTTP 404）。"
        408 -> return "$provider 服务端报告请求超时（HTTP 408）。"
        413 -> return "$provider 请求内容过大（HTTP 413）。"
        429 -> return "$provider 限制了请求频率（HTTP 429），请稍后重试。"
        in 500..599 -> return "$provider 返回 HTTP $httpStatus 错误，具体内部原因无法确认。"
        in 100..599 -> return "$provider 返回 HTTP $httpStatus，具体原因无法确认。"
    }
    if (responseParsingFailed) return "$provider 响应解析或格式校验失败。"
    if (providerReportedFailure) return "$provider 报告请求失败，具体原因无法确认。"
    if (causes.any { it is SocketTimeoutException }) return "等待 $provider 响应超时。"
    if (causes.any { it is UnknownHostException }) return "无法解析 $provider 域名，请检查服务地址和网络。"
    if (causes.any { it is SSLException }) return "无法建立与 $provider 的安全连接。"
    if (causes.any { it is ConnectException || it is NoRouteToHostException }) {
        return "无法连接 $provider，请检查服务地址和网络。"
    }
    if (causes.any { it is SocketException }) return "$provider 连接中断，具体阶段无法确认。"
    if (causes.any { it is IOException }) return "$provider 语音连接传输失败，具体原因无法确认。"
    return "$provider 连接失败，具体原因无法确认。"
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this@causeChain
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}
