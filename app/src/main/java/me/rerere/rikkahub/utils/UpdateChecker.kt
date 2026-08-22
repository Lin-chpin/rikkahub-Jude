package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Build
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
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
    "app-public-universal-debug.apk",
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

enum class UpdateDownloadFailureReason {
    Network,
    ResourceUnavailable,
    ServiceUnavailable,
    TooManyRedirects,
    InsufficientSpace,
    FileAlreadyExists,
    CannotResume,
    StorageUnavailable,
    Unknown,
}

sealed interface UpdateDownloadState {
    data class Downloading(
        val id: Long,
        val download: UpdateDownload,
    ) : UpdateDownloadState

    data class Completed(
        val id: Long,
        val download: UpdateDownload,
    ) : UpdateDownloadState

    data class Failed(
        val id: Long,
        val download: UpdateDownload,
        val reason: UpdateDownloadFailureReason,
        val httpStatusCode: Int? = null,
        val systemReason: Int? = null,
    ) : UpdateDownloadState
}

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val downloadLock = Any()
    private val trackedDownloads = mutableMapOf<Long, UpdateDownload>()
    private var downloadReceiverRegistered = false
    private val _downloadState = MutableStateFlow<UpdateDownloadState?>(null)
    val downloadState: StateFlow<UpdateDownloadState?> = _downloadState.asStateFlow()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, INVALID_DOWNLOAD_ID)
            if (downloadId == INVALID_DOWNLOAD_ID) return
            val download = synchronized(downloadLock) {
                trackedDownloads[downloadId]
            } ?: readPendingDownload(context.applicationContext)
                ?.takeIf { it.id == downloadId }
                ?.download
                ?: return
            inspectDownload(context.applicationContext, downloadId, download)
        }
    }

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
                            val release = json.decodeFromString<GithubRelease>(it.body.string())
                            val hasNewerVersion = Version(release.tagName) > Version(BuildConfig.VERSION_NAME)
                            release.toUpdateInfo(requireDownloadAsset = hasNewerVersion)
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
        val appContext = context.applicationContext
        ensureDownloadReceiver(appContext)
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
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            synchronized(downloadLock) {
                trackedDownloads[downloadId] = download
            }
            savePendingDownload(appContext, downloadId, download)
            _downloadState.value = UpdateDownloadState.Downloading(downloadId, download)
        }.onFailure {
            _downloadState.value = UpdateDownloadState.Failed(
                id = INVALID_DOWNLOAD_ID,
                download = download,
                reason = classifyImmediateDownloadFailure(it),
            )
        }
    }

    fun retryDownload(context: Context, failure: UpdateDownloadState.Failed) {
        val appContext = context.applicationContext
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (failure.id != INVALID_DOWNLOAD_ID) {
            runCatching { dm.remove(failure.id) }
        }
        clearPendingDownload(appContext)
        synchronized(downloadLock) {
            trackedDownloads.remove(failure.id)
        }
        downloadUpdate(appContext, failure.download)
    }

    fun restoreDownloadState(context: Context) {
        val appContext = context.applicationContext
        val pending = readPendingDownload(appContext) ?: return
        ensureDownloadReceiver(appContext)
        inspectDownload(appContext, pending.id, pending.download)
    }

    fun clearDownloadState(context: Context) {
        clearPendingDownload(context.applicationContext)
        _downloadState.value = null
    }

    private fun ensureDownloadReceiver(context: Context) {
        synchronized(downloadLock) {
            if (downloadReceiverRegistered) return
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // DownloadManager broadcasts from the system download provider, not this app.
                context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(downloadReceiver, filter)
            }
            downloadReceiverRegistered = true
        }
    }

    private fun inspectDownload(context: Context, downloadId: Long, download: UpdateDownload) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val snapshot = dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                DownloadSnapshot(
                    status = cursor.getRequiredInt(DOWNLOAD_STATUS_COLUMN),
                    reason = cursor.getOptionalInt(DOWNLOAD_REASON_COLUMN),
                    httpStatusCode = cursor.getOptionalInt(DOWNLOAD_HTTP_STATUS_COLUMN),
                )
            }
        }

        if (snapshot == null) {
            clearPendingDownload(context)
            _downloadState.value = UpdateDownloadState.Failed(
                id = downloadId,
                download = download,
                reason = UpdateDownloadFailureReason.Unknown,
            )
            return
        }

        when (snapshot.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                clearPendingDownload(context)
                synchronized(downloadLock) {
                    trackedDownloads.remove(downloadId)
                }
                _downloadState.value = UpdateDownloadState.Completed(downloadId, download)
            }

            DownloadManager.STATUS_FAILED -> {
                _downloadState.value = UpdateDownloadState.Failed(
                    id = downloadId,
                    download = download,
                    reason = classifyDownloadFailure(snapshot.reason, snapshot.httpStatusCode),
                    httpStatusCode = snapshot.httpStatusCode,
                    systemReason = snapshot.reason,
                )
            }

            else -> {
                _downloadState.value = UpdateDownloadState.Downloading(downloadId, download)
            }
        }
    }

    private fun savePendingDownload(context: Context, downloadId: Long, download: UpdateDownload) {
        context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(DOWNLOAD_ID_KEY, downloadId)
            .putString(DOWNLOAD_INFO_KEY, json.encodeToString(download))
            .apply()
    }

    private fun readPendingDownload(context: Context): PendingDownload? {
        val preferences = context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
        val id = preferences.getLong(DOWNLOAD_ID_KEY, INVALID_DOWNLOAD_ID)
        val encodedDownload = preferences.getString(DOWNLOAD_INFO_KEY, null) ?: return null
        if (id == INVALID_DOWNLOAD_ID) return null
        return runCatching {
            PendingDownload(id, json.decodeFromString<UpdateDownload>(encodedDownload))
        }.getOrNull()
    }

    private fun clearPendingDownload(context: Context) {
        context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

private data class PendingDownload(
    val id: Long,
    val download: UpdateDownload,
)

private data class DownloadSnapshot(
    val status: Int,
    val reason: Int?,
    val httpStatusCode: Int?,
)

private const val DOWNLOAD_PREFS = "update_download_state"
private const val DOWNLOAD_ID_KEY = "download_id"
private const val DOWNLOAD_INFO_KEY = "download_info"
private const val INVALID_DOWNLOAD_ID = -1L
private const val DOWNLOAD_STATUS_COLUMN = "status"
private const val DOWNLOAD_REASON_COLUMN = "reason"
private const val DOWNLOAD_HTTP_STATUS_COLUMN = "http_status_code"

private fun Cursor.getRequiredInt(columnName: String): Int {
    val index = getColumnIndex(columnName)
    check(index >= 0) { "DownloadManager column is missing: $columnName" }
    return getInt(index)
}

private fun Cursor.getOptionalInt(columnName: String): Int? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) return null
    return getInt(index).takeIf { it > 0 }
}

internal fun classifyDownloadFailure(reason: Int?, httpStatusCode: Int?): UpdateDownloadFailureReason {
    return when {
        httpStatusCode in 500..599 -> UpdateDownloadFailureReason.ServiceUnavailable
        httpStatusCode == 401 || httpStatusCode == 403 || httpStatusCode == 404 ->
            UpdateDownloadFailureReason.ResourceUnavailable
        reason == DownloadManager.ERROR_INSUFFICIENT_SPACE -> UpdateDownloadFailureReason.InsufficientSpace
        reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS -> UpdateDownloadFailureReason.FileAlreadyExists
        reason == DownloadManager.ERROR_CANNOT_RESUME -> UpdateDownloadFailureReason.CannotResume
        reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS -> UpdateDownloadFailureReason.TooManyRedirects
        reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> UpdateDownloadFailureReason.ResourceUnavailable
        reason == DownloadManager.ERROR_HTTP_DATA_ERROR -> UpdateDownloadFailureReason.Network
        reason == DownloadManager.ERROR_DEVICE_NOT_FOUND || reason == DownloadManager.ERROR_FILE_ERROR ->
            UpdateDownloadFailureReason.StorageUnavailable
        else -> UpdateDownloadFailureReason.Unknown
    }
}

private fun classifyImmediateDownloadFailure(error: Throwable): UpdateDownloadFailureReason {
    return when (error) {
        is SecurityException -> UpdateDownloadFailureReason.StorageUnavailable
        is IOException -> UpdateDownloadFailureReason.Network
        else -> UpdateDownloadFailureReason.Unknown
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

private fun GithubRelease.toUpdateInfo(requireDownloadAsset: Boolean): UpdateInfo {
    val asset = UPDATE_ASSET_NAMES.firstNotNullOfOrNull { assetName ->
        assets.firstOrNull { it.name == assetName }
    }
    if (requireDownloadAsset && asset == null) {
        error("Update asset not found: ${UPDATE_ASSET_NAMES.joinToString()}")
    }
    return UpdateInfo(
        version = tagName.removePrefix("v"),
        publishedAt = publishedAt,
        changelog = body?.takeIf { it.isNotBlank() } ?: "No changelog provided.",
        downloads = asset?.let {
            listOf(
                UpdateDownload(
                    name = it.name,
                    url = it.browserDownloadUrl,
                    size = formatBytes(it.size),
                )
            )
        } ?: emptyList(),
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
