package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Stages restore files and installs them atomically before app initialization. */
internal class PendingRestore(
    private val root: File,
    private val databaseFile: File,
    private val filesDir: File,
) {
    private val pending get() = File(root, "pending")

    fun createStagingDirectory(): File {
        check(!pending.exists()) { "A backup restore is already pending. Restart the app first." }
        check(root.isDirectory || root.mkdirs()) { "Cannot create backup staging directory" }
        return Files.createTempDirectory(root.toPath(), "preparing-").toFile()
    }

    fun publish(staging: File) {
        check(!pending.exists()) { "A backup restore is already pending. Restart the app first." }
        move(staging, pending)
    }

    suspend fun apply(restoreSettings: suspend (String) -> Unit): Boolean {
        root.listFiles()?.filter {
            it.name.startsWith("completed-") || it.name.startsWith("preparing-")
        }?.forEach { it.deleteRecursively() }
        if (!pending.isDirectory) return false

        val journal = File(pending, "journal.json")
        val entries = if (journal.exists()) {
            Json.decodeFromString<List<RestoreEntry>>(journal.readText())
        } else {
            try {
                buildEntries().also { writeDurably(journal, Json.encodeToString(it)) }
            } catch (e: Exception) {
                move(pending, File(root, "failed-${UUID.randomUUID()}"))
                throw RestoreFailedException(e)
            }
        }
        try {
            entries.forEach { entry ->
                val target = targetFile(entry.path)
                val original = File(pending, "originals/${entry.path}")
                val source = File(pending, "payload/${entry.path}")
                if (entry.install && !source.exists()) {
                    check(target.isFile) { "Interrupted restore is missing ${entry.path}" }
                } else {
                    if (entry.hadOriginal && !original.exists()) move(target, original)
                    if (entry.install) move(source, target)
                }
            }
            val settings = File(pending, "settings.json")
            if (settings.isFile) restoreSettings(settings.readText())
        } catch (e: Exception) {
            try {
                rollback(entries)
            } catch (rollbackError: Exception) {
                e.addSuppressed(rollbackError)
                throw e
            }
            move(pending, File(root, "failed-${UUID.randomUUID()}"))
            throw RestoreFailedException(e)
        }

        val completed = File(root, "completed-${UUID.randomUUID()}")
        move(pending, completed)
        completed.deleteRecursively()
        return true
    }

    private fun buildEntries(): List<RestoreEntry> {
        val payload = File(pending, "payload")
        val paths = payload.walkTopDown().filter { it.isFile }.map {
            it.relativeTo(payload).invariantSeparatorsPath
        }.toList().sorted()
        val entries = paths.map { path ->
            RestoreEntry(path, install = true, hadOriginal = targetFile(path).exists())
        }.toMutableList()
        if (paths.contains("database/${databaseFile.name}")) {
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                val path = "database/${databaseFile.name}$suffix"
                entries.add(0, RestoreEntry(path, install = false, hadOriginal = targetFile(path).exists()))
            }
        }
        return entries
    }

    private fun rollback(entries: List<RestoreEntry>) {
        entries.asReversed().forEach { entry ->
            val target = targetFile(entry.path)
            val original = File(pending, "originals/${entry.path}")
            val source = File(pending, "payload/${entry.path}")
            if (entry.install && !source.exists() && target.exists()) move(target, source)
            if (original.exists()) move(original, target)
        }
    }

    private fun targetFile(path: String): File = when {
        path.startsWith("database/") -> {
            val name = path.removePrefix("database/")
            require(name in listOf(
                databaseFile.name,
                databaseFile.name + "-wal",
                databaseFile.name + "-shm",
                databaseFile.name + "-journal",
            )) { "Invalid restore database path" }
            File(databaseFile.parentFile, name)
        }
        path.startsWith("files/") -> resolveInside(filesDir, path.removePrefix("files/"))
        else -> error("Invalid restore path: $path")
    }

    @Serializable
    private data class RestoreEntry(val path: String, val install: Boolean, val hadOriginal: Boolean)

    companion object {
        fun resolveInside(root: File, relativePath: String): File {
            require(
                relativePath.isNotBlank() && !relativePath.startsWith('/') &&
                    '\\' !in relativePath && relativePath.split('/').none { it == ".." || it == "." }
            ) { "Invalid backup file path: $relativePath" }
            val target = File(root, relativePath).canonicalFile
            require(target.path.startsWith(root.canonicalPath + File.separator)) {
                "Backup file is outside its target directory"
            }
            return target
        }

        fun writeDurably(file: File, text: String) {
            val temporary = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            move(temporary, file)
        }

        private fun move(source: File, target: File) {
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create restore directory" }
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }
}

internal class RestoreFailedException(cause: Exception) :
    Exception("Backup restore failed; original files restored", cause)
