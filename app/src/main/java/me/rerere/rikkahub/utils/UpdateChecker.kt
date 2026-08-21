package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale

private const val API_URL = "https://api.github.com/repos/Lin-chpin/rikkahub-Jude/releases/latest"
const val UPDATE_RELEASES_URL = "https://github.com/Lin-chpin/rikkahub-Jude/releases"
private val UPDATE_ASSET_NAMES = listOf(
    "app-universal-debug.apk",
    "app-arm64-v8a-debug.apk",
)

enum class UpdateFailureReason {
    RateLimited,
    Network,
    SourceUnavailable,
    ServiceUnavailable,
    Unknown,
}

class UpdateCheckException(
    val reason: UpdateFailureReason,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(null, cause)

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    val response = client.newCall(
                        Request.Builder()
                            .url(API_URL)
                            .get()
                            .addHeader(
                                "User-Agent",
                                "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                            )
                            .build()
                    ).await()
                    response.use {
                        if (!it.isSuccessful) {
                            throw it.toUpdateCheckException()
                        }
                        runCatching {
                            json.decodeFromString<GithubRelease>(it.body.string()).toUpdateInfo()
                        }.getOrElse { cause ->
                            throw UpdateCheckException(
                                reason = UpdateFailureReason.SourceUnavailable,
                                cause = cause,
                            )
                        }
                    }
                } catch (e: UpdateCheckException) {
                    throw e
                } catch (e: IOException) {
                    throw UpdateCheckException(UpdateFailureReason.Network, cause = e)
                } catch (e: Exception) {
                    throw UpdateCheckException(UpdateFailureReason.Unknown, cause = e)
                }
            )
        )
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                // 设置下载时通知栏的标题和描述
                setTitle(download.name)
                setDescription("正在下载更新包...")
                // 下载完成后通知栏可见
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // 允许在移动网络和WiFi下下载
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                // 设置文件保存路径
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                // 允许下载的文件类型
                setMimeType("application/vnd.android.package-archive")
            }
            // 获取系统的DownloadManager
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            // 你可以保存返回的downloadId到本地，以便后续查询下载进度或状态
        }.onFailure {
            Toast.makeText(context, "Failed to update", Toast.LENGTH_SHORT).show()
            context.openUrl(UPDATE_RELEASES_URL) // 下载失败时打开 Release 页面兜底
        }
    }
}

private fun Response.toUpdateCheckException(): UpdateCheckException {
    val responseBody = body.string()
    val rateLimitRemaining = header("X-RateLimit-Remaining")
    val isRateLimited = code == 429 ||
        (code == 403 && (rateLimitRemaining == "0" || responseBody.contains("rate limit", ignoreCase = true)))
    return when {
        isRateLimited -> UpdateCheckException(UpdateFailureReason.RateLimited, statusCode = code)
        code in 500..599 -> UpdateCheckException(UpdateFailureReason.ServiceUnavailable, statusCode = code)
        else -> UpdateCheckException(UpdateFailureReason.SourceUnavailable, statusCode = code)
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("published_at")
    val publishedAt: String,
    val body: String? = null,
    val assets: List<GithubReleaseAsset> = emptyList(),
)

@Serializable
private data class GithubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long = 0L,
)

private fun GithubRelease.toUpdateInfo(): UpdateInfo {
    val asset = UPDATE_ASSET_NAMES.firstNotNullOfOrNull { assetName ->
        assets.firstOrNull { it.name == assetName }
    } ?: error("Update asset not found: ${UPDATE_ASSET_NAMES.joinToString()}")
    return UpdateInfo(
        version = tagName.removePrefix("v"),
        publishedAt = publishedAt,
        changelog = body?.takeIf { it.isNotBlank() } ?: "No changelog provided.",
        downloads = listOf(
            UpdateDownload(
                name = asset.name,
                url = asset.browserDownloadUrl,
                size = formatBytes(asset.size),
            )
        ),
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${bytes} ${units[unitIndex]}"
    } else {
        "%.1f %s".format(Locale.US, value, units[unitIndex])
    }
}

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.trim().removePrefix("v").removePrefix("V").split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
