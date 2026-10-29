package me.rerere.rikkahub.data.ai

import me.rerere.common.http.privateFailureStatusCode
import me.rerere.ai.util.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private val httpStatusPattern = Regex(
    "(?:Failed to get response:|HTTP(?:\\s+error|\\s+status)?[: ]+)\\s*(\\d{3})(?!\\d)",
    RegexOption.IGNORE_CASE,
)

fun Throwable.chatErrorExplanation(): String {
    val status = (this as? HttpException)?.statusCode ?: privateFailureStatusCode(this)
        ?: httpStatusPattern.find(message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    return when {
        this is EmptyAssistantResponseException -> message.orEmpty()
        status == 400 -> "接口未接受本次请求，请查看错误详情，检查模型参数和消息内容。"
        status == 401 -> "接口身份验证失败，请检查 API 密钥或账号登录状态。"
        status == 403 -> "接口拒绝访问，请检查账号权限及模型访问权限。"
        status == 404 -> "接口地址或模型未找到，请检查供应商地址和模型名称。"
        status == 408 -> "接口处理请求超时，你可以稍后手动重试。"
        status == 413 -> "本次请求过大，请减少上下文或附件后重试。"
        status == 429 -> "接口限制了请求频率或额度，请稍后重试并检查账号配额。"
        status != null && status in 500..599 -> "接口暂时无法完成请求，请稍后手动重试。"
        this is SocketTimeoutException -> "等待接口响应超时。已收到的内容会保留，你可以稍后手动重试。"
        this is UnknownHostException -> "无法解析接口域名，请检查接口地址和网络连接。"
        this is SSLException -> "无法建立安全连接，请检查接口证书和网络环境。"
        this is IOException -> "请求连接失败或中断。已收到的内容会保留，请检查网络及接口连接后手动重试。"
        message.orEmpty().any { it in '\u4e00'..'\u9fff' } -> message.orEmpty()
        else -> "本次操作未能完成，请查看错误详情了解具体原因。"
    }
}
