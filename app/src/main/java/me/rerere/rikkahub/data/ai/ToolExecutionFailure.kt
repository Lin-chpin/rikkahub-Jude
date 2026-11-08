package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.SerializationException
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderResponseParseException
import me.rerere.rikkahub.data.ai.mcp.transport.StreamableHttpError
import me.rerere.search.SearchToolArgumentException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal class ToolExecutionException(
    val stage: String,
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)

internal fun toolExecutionFailure(stage: String, error: Throwable? = null): String {
    val failure = error as? ToolExecutionException
    val causes = error?.causeChain()?.toList().orEmpty()
    val httpFailures = causes.filterIsInstance<HttpException>()
    val mcpTransportFailure = causes.filterIsInstance<StreamableHttpError>().firstOrNull()
    val status = httpFailures.firstNotNullOfOrNull { it.statusCode }
        ?: mcpTransportFailure?.code
    val providerFailure = httpFailures.firstOrNull {
        it.providerType == "configuration" || it.providerType == "remote_service"
    }
    val toolArgumentFailure = causes.filterIsInstance<SearchToolArgumentException>().firstOrNull()
    val providerCode = providerFailure?.providerCode?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
    val isNetworkFailure = causes.any { it.isNetworkFailure() }
    val interruptedSocket = causes.any {
        it is SocketException && it !is ConnectException && it !is NoRouteToHostException
    }
    val actualStage = when {
        status != null -> "remote_service"
        mcpTransportFailure?.responseFormatInvalid == true ||
            causes.any { it is SerializationException || it is ProviderResponseParseException } -> "response_parsing"
        toolArgumentFailure != null -> "argument_parsing"
        providerFailure?.providerType == "configuration" -> "configuration"
        providerFailure?.providerType == "remote_service" -> "remote_service"
        isNetworkFailure -> "connection"
        else -> failure?.stage ?: stage
    }
    val code = when {
        status != null -> "http_failure"
        actualStage == "resolution" -> "tool_not_found"
        actualStage == "argument_parsing" -> "invalid_arguments"
        actualStage == "response_parsing" -> "response_format_invalid"
        actualStage == "configuration" -> providerCode ?: "configuration_error"
        actualStage == "remote_service" -> "provider_reported_failure"
        actualStage == "connection" -> if (interruptedSocket) "connection_interrupted" else failure?.code ?: "connection_failed"
        actualStage == "remote_tool" -> failure?.code ?: "remote_tool_failed"
        else -> failure?.code ?: "tool_execution_failed"
    }
    return buildJsonObject {
        put("error", buildJsonObject {
            put("code", code)
            put("stage", actualStage)
            status?.let { put("httpStatus", it) }
            if (actualStage == "remote_service") providerCode?.let { put("providerCode", it) }
            if (actualStage == "argument_parsing") toolArgumentFailure?.argument?.let { put("argument", it) }
            put("message", "Tool operation failed")
        })
    }.toString()
}

internal fun JsonElement?.toolExecutionFailureMessage(): String? {
    val error = (this as? JsonObject)?.get("error") ?: return null
    val details = error as? JsonObject ?: return null
    val marker = (details["message"] as? JsonPrimitive)?.contentOrNull
    val stage = (details["stage"] as? JsonPrimitive)?.contentOrNull
    if (marker != "Tool operation failed" || stage !in TOOL_FAILURE_STAGES) return null
    val code = (details["code"] as? JsonPrimitive)?.contentOrNull
    val providerCode = (details["providerCode"] as? JsonPrimitive)?.contentOrNull
    val status = (details["httpStatus"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    return when {
        stage == "resolution" -> "找不到对应的工具。"
        stage == "argument_parsing" -> "工具参数缺少必填项或格式无效。"
        stage == "response_parsing" -> "工具响应解析或格式校验失败。"
        providerCode in setOf("invalid_api_key", "authentication_error", "unauthorized") -> "第三方服务身份验证失败。"
        providerCode in setOf("payment_issue", "payment_required", "insufficient_quota", "quota_exceeded", "billing_hard_limit_reached") ->
            "第三方服务要求检查账户付款状态或可用额度。"
        providerCode in setOf("rate_limit_exceeded", "rate_limited", "too_many_requests") -> "第三方服务限制了请求频率。"
        providerCode in setOf("permission_denied", "access_denied", "forbidden") -> "第三方服务拒绝访问，请检查权限。"
        providerCode in setOf("model_not_found", "unknown_model", "not_found") -> "第三方服务未找到请求的模型或资源。"
        providerCode in setOf("invalid_request_error", "invalid_parameter", "unsupported_parameter", "bad_request") ->
            "第三方服务拒绝了请求，请检查参数。"
        status == 401 -> "第三方服务身份验证失败（HTTP 401）。"
        status == 403 -> "第三方服务拒绝访问（HTTP 403）。"
        status == 408 -> "第三方服务报告请求超时（HTTP 408）。"
        status == 429 -> "第三方服务限制了请求频率或额度（HTTP 429）。"
        status == 413 -> "第三方服务拒绝了过大的请求（HTTP 413）。"
        status == 400 -> "第三方服务拒绝请求（HTTP 400）。"
        status == 404 -> "第三方服务未找到请求的资源（HTTP 404）。"
        status != null && status in 500..599 -> "第三方服务返回错误（HTTP $status）。"
        status != null -> "第三方服务返回 HTTP $status。"
        stage == "configuration" && code == "missing_api_key" -> "搜索服务未配置 API Key，请检查服务设置。"
        stage == "configuration" && code == "missing_search_script" -> "自定义搜索服务未配置搜索脚本，请检查脚本设置。"
        stage == "configuration" && code == "missing_scrape_script" -> "自定义抓取服务未配置抓取脚本，请检查脚本设置。"
        stage == "configuration" && code == "missing_service_url" -> "搜索服务未配置服务地址，请检查服务设置。"
        stage == "configuration" -> "搜索服务配置无效，请检查服务设置。"
        stage == "remote_service" -> "第三方服务报告请求失败。"
        stage == "remote_tool" -> "第三方工具报告执行失败。"
        stage == "connection" && code == "connection_interrupted" -> "工具连接中断，具体阶段无法确认。"
        stage == "connection" && code == "connection_failed" -> "工具连接失败。"
        stage == "connection" -> "工具连接不可用。"
        else -> "工具执行失败。"
    }
}

private val TOOL_FAILURE_STAGES = setOf(
    "resolution",
    "argument_parsing",
    "response_parsing",
    "configuration",
    "remote_service",
    "connection",
    "remote_tool",
    "execution",
)

private fun Throwable.isNetworkFailure(): Boolean = when (this) {
    is ConnectException,
    is NoRouteToHostException,
    is SocketException,
    is SocketTimeoutException,
    is UnknownHostException,
    is SSLException -> true
    else -> false
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this@causeChain
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}
