package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.time.Instant

private const val TAG = "WebDavSync"

class WebDavSync(
    private val backupManager: BackupManager,
    private val context: Context,
    private val httpClient: HttpClient,
) {
    private fun getClient(config: WebDavConfig): WebDavClient = WebDavClient(config, httpClient)

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        getClient(config).propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        try {
            val client = getClient(config)
            client.ensureCollectionExists().getOrThrow()
            client.put(path = file.name, file = file, contentType = "application/zip").getOrThrow()
            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.ensureCollectionExists().getOrThrow()
        client.list().getOrThrow()
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH,
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val backupFile = File.createTempFile("restore-", ".zip", context.cacheDir)
        try {
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            getClient(config).downloadToFile(item.displayName, backupFile).getOrThrow()
            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")
            restoreFromBackupFile(backupFile, config)
        } finally {
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        getClient(config).delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) {
        restoreFromBackupFile(file, config)
    }

    suspend fun prepareBackupFile(config: WebDavConfig): File = backupManager.createBackup(
        includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
        includeFiles = WebDavConfig.BackupItem.FILES in config.items,
    )

    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig) =
        backupManager.stageRestore(
            archive = backupFile,
            includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
            includeFiles = WebDavConfig.BackupItem.FILES in config.items,
        )
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
