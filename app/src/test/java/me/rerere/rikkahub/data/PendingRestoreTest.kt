package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PendingRestoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun failedSettingsWriteRollsBackDatabaseAndFiles() = runBlocking {
        val database = write("databases/rikka_hub", "old database")
        val photo = write("files/upload/photo", "old photo")
        val restore = PendingRestore(
            root = File(temporary.root, "restore"),
            databaseFile = database,
            filesDir = File(temporary.root, "files"),
        )
        val staging = restore.createStagingDirectory()
        write(File(staging, "payload/database/rikka_hub"), "new database")
        write(File(staging, "payload/files/upload/photo"), "new photo")
        write(File(staging, "settings.json"), "new settings")
        restore.publish(staging)

        assertThrows(RestoreFailedException::class.java) {
            runBlocking { restore.apply { throw IOException("Disk full") } }
        }
        assertEquals("old database", database.readText())
        assertEquals("old photo", photo.readText())
    }

    @Test
    fun rejectsArchiveTraversalPaths() {
        for (path in listOf("../database", "/absolute", "skills/../../database", "skills/..\\database")) {
            assertThrows(IllegalArgumentException::class.java) {
                PendingRestore.resolveInside(temporary.root, path)
            }
        }
    }

    private fun write(path: String, contents: String) = write(File(temporary.root, path), contents)

    private fun write(file: File, contents: String): File {
        file.parentFile!!.mkdirs()
        file.writeText(contents)
        return file
    }
}
