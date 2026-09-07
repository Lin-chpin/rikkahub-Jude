package me.rerere.rikkahub.data

import kotlinx.serialization.json.jsonArray
import me.rerere.rikkahub.data.sync.transfer.RIKKAHUB_TRANSFER_FORMAT
import me.rerere.rikkahub.data.sync.transfer.RIKKAHUB_TRANSFER_FORMAT_VERSION
import me.rerere.rikkahub.data.sync.transfer.RikkaHubTransferConversation
import me.rerere.rikkahub.data.sync.transfer.RikkaHubTransferImporter
import me.rerere.rikkahub.data.sync.transfer.RikkaHubTransferManifest
import me.rerere.rikkahub.data.sync.transfer.RikkaHubTransferMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class RikkaHubTransferImporterTest {
    @Test
    fun importsVersionedTransferPackageAndMigratesLegacyPartType() {
        val file = File.createTempFile("rikkahub-transfer-test-", ".rhk")
        try {
            val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000001")
            val conversationId = "00000000-0000-0000-0000-000000000002"
            val nodeId = "00000000-0000-0000-0000-000000000003"
            val messages = JsonInstant.parseToJsonElement(
                """[{"id":"00000000-0000-0000-0000-000000000004","role":"user","parts":[{"type":"UIMessagePart.Text","text":"hello"}]}]"""
            )
            val manifest = RikkaHubTransferManifest(
                format = RIKKAHUB_TRANSFER_FORMAT,
                formatVersion = RIKKAHUB_TRANSFER_FORMAT_VERSION,
                sourceApp = "test-adapter",
                conversationCount = 1,
            )
            val conversation = RikkaHubTransferConversation(
                id = conversationId,
                sourceId = "source-1",
                title = "Imported",
                createAt = 1_000,
                updateAt = 2_000,
                messageNodes = listOf(
                    RikkaHubTransferMessageNode(
                        id = nodeId,
                        messages = messages.jsonArray,
                    )
                ),
            )

            ZipOutputStream(FileOutputStream(file)).use { zip ->
                putJsonEntry(zip, "manifest.json", JsonInstant.encodeToString(manifest))
                putJsonEntry(zip, "conversations.json", JsonInstant.encodeToString(listOf(conversation)))
            }

            val result = RikkaHubTransferImporter.import(file, assistantId)

            assertEquals(1, result.conversations.size)
            assertEquals("Imported", result.conversations.single().title)
            assertEquals(1, result.conversations.single().messageNodes.size)
            assertEquals("hello", result.conversations.single().messageNodes.single().messages.single().toText())
            assertTrue(result.errors.isEmpty())
        } finally {
            file.delete()
        }
    }

    private fun putJsonEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }
}
