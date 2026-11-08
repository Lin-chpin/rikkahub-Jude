package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.s3.S3Exception
import me.rerere.rikkahub.data.sync.webdav.WebDavException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal fun Throwable.backupSyncErrorMessage(): String {
    val causes = causeChain().toList()
    val webDavFailure = causes.filterIsInstance<WebDavException>().firstOrNull()
    val s3Failure = causes.filterIsInstance<S3Exception>().firstOrNull()
    val provider = when {
        webDavFailure != null -> "WebDAV"
        s3Failure != null -> "S3 兼容存储服务"
        else -> "备份服务"
    }
    val status = webDavFailure?.statusCode ?: s3Failure?.statusCode
    when (status) {
        400 -> return "$provider 拒绝了本次请求（HTTP 400）。"
        401 -> return "$provider 身份验证失败（HTTP 401），请检查账号和凭据。"
        402 -> return "$provider 要求检查账户付款状态或额度（HTTP 402）。"
        403 -> return "$provider 拒绝访问（HTTP 403），请检查账号权限。"
        404 -> return "$provider 未找到请求的地址或资源（HTTP 404），请检查服务地址和备份路径。"
        408 -> return "$provider 报告请求超时（HTTP 408），请稍后重试。"
        413 -> return "备份请求过大（HTTP 413），请减少备份内容后重试。"
        429 -> return "$provider 限制了请求频率（HTTP 429），请稍后重试。"
        in 500..599 -> return "$provider 返回 HTTP $status 错误，具体内部原因无法确认。"
        else -> if (status != null) return "$provider 返回 HTTP $status，具体原因无法确认。"
    }
    if (causes.any { it is SocketTimeoutException }) return "连接备份服务超时。"
    if (causes.any { it is UnknownHostException }) return "无法解析备份服务域名，请检查服务器地址和网络。"
    if (causes.any { it is SSLException }) return "无法建立备份服务安全连接。"
    if (causes.any { it is ConnectException || it is NoRouteToHostException }) {
        return "无法连接备份服务，请检查服务器地址和网络。"
    }
    if (causes.any { it is SocketException }) return "备份服务连接中断，具体阶段无法确认。"
    if (causes.any { it is IOException }) return "备份传输阶段失败，具体原因无法确认。"
    return "备份操作失败，具体原因无法确认。"
}

internal fun Throwable.backupSyncDiagnostic(operation: String): String {
    val causes = causeChain().toList()
    val status = causes.filterIsInstance<WebDavException>().firstOrNull()?.statusCode
        ?: causes.filterIsInstance<S3Exception>().firstOrNull()?.statusCode
    return buildString {
        append("operation=$operation cause_types=")
        append(causes.joinToString(">") { it::class.simpleName ?: "Throwable" })
        status?.let { append(" http_status=$it") }
    }
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this@causeChain
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}
