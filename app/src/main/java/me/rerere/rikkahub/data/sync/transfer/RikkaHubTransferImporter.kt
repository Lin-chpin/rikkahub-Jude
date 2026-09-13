package me.rerere.rikkahub.data.sync.transfer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.migrateToolNodes
import me.rerere.rikkahub.data.db.migrations.migrateMessagesJson
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

data class RikkaHubTransferImportReport(
    val parsedConversations: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedConversations: Int,
    val skippedNodes: Int,
    val parsedMessages: Int,
    val warnings: List<String>,
    val errors: List<String>,
    val replacedAllData: Boolean = false,
) {
    val hasWarnings: Boolean get() = warnings.isNotEmpty() || errors.isNotEmpty()

    fun toDiagnosticText(): String = buildString {
        appendLine("format=$RIKKAHUB_TRANSFER_FORMAT/$RIKKAHUB_TRANSFER_FORMAT_VERSION")
        appendLine("parsed_conversations=$parsedConversations")
        appendLine("imported_conversations=$importedConversations")
        appendLine("skipped_existing_conversations=$skippedExistingConversations")
        appendLine("skipped_conversations=$skippedConversations")
        appendLine("skipped_nodes=$skippedNodes")
        appendLine("parsed_messages=$parsedMessages")
        appendLine("replaced_all_data=$replacedAllData")
        warnings.forEach { appendLine("warning=$it") }
        errors.forEach { appendLine("error=$it") }
    }
}

data class RikkaHubTransferImportResult(
    val manifest: RikkaHubTransferManifest,
    val conversations: List<Conversation>,
    val skippedConversations: Int,
    val skippedNodes: Int,
    val parsedMessages: Int,
    val warnings: List<String>,
    val errors: List<String>,
    val attachments: Map<String, RikkaHubTransferAttachmentData>,
    val settingsJson: String? = null,
    val files: List<RikkaHubTransferFileData> = emptyList(),
) {
    val isCompleteRestore: Boolean
        get() = manifest.completeRestore && settingsJson != null
}

data class RikkaHubTransferAttachmentData(
    val descriptor: RikkaHubTransferAttachment,
    val bytes: ByteArray,
)

data class RikkaHubTransferFileData(
    val descriptor: RikkaHubTransferFile,
    val bytes: ByteArray,
)

object RikkaHubTransferImporter {
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val CONVERSATIONS_ENTRY = "conversations.json"
    private const val SETTINGS_ENTRY = "settings.json"

    fun import(file: File, assistantId: Uuid): RikkaHubTransferImportResult {
        ZipFile(file).use { zip ->
            val manifest = zip.readJsonEntry<RikkaHubTransferManifest>(MANIFEST_ENTRY)
            require(manifest.format == RIKKAHUB_TRANSFER_FORMAT) {
                "Unsupported transfer format: ${manifest.format}"
            }
            require(manifest.formatVersion == RIKKAHUB_TRANSFER_FORMAT_VERSION) {
                "Unsupported transfer format version: ${manifest.formatVersion}"
            }

            val transferConversations = zip.readJsonEntry<List<RikkaHubTransferConversation>>(
                CONVERSATIONS_ENTRY
            )
            val diagnostics = zip.readJsonEntryOrNull<RikkaHubTransferDiagnostics>("diagnostics.json")
            val errors = mutableListOf<String>()
            val files = manifest.files.mapNotNull { descriptor ->
                zip.getEntry(descriptor.entry)?.let { entry ->
                    RikkaHubTransferFileData(
                        descriptor = descriptor,
                        bytes = zip.getInputStream(entry).use { it.readBytes() },
                    )
                } ?: run {
                    errors += "file:${descriptor.relativePath}:missing entry ${descriptor.entry}"
                    null
                }
            }
            val availableFiles = files.associateBy { it.descriptor.relativePath }
            val attachments = manifest.attachments.mapNotNull { descriptor ->
                val fileBackedAttachment = descriptor.sourceRelativePath?.let {
                    it in availableFiles
                } == true
                zip.getEntry(descriptor.entry)?.let { entry ->
                    RikkaHubTransferAttachmentData(
                        descriptor = descriptor,
                        bytes = if (fileBackedAttachment) {
                            ByteArray(0)
                        } else {
                            zip.getInputStream(entry).use { it.readBytes() }
                        },
                    )
                } ?: run {
                    errors += "attachment:${descriptor.id}:missing entry ${descriptor.entry}"
                    null
                }
            }.associateBy { it.descriptor.id }
            val settingsJson = zip.getEntry(SETTINGS_ENTRY)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
            var skippedConversations = 0
            var skippedNodes = 0
            var parsedMessages = 0
            val conversations = transferConversations.mapNotNull { source ->
                runCatching {
                    val parsedNodes = source.messageNodes.mapNotNull { node ->
                        runCatching {
                            val messages = decodeMessages(node.messages)
                            parsedMessages += messages.size
                            MessageNode(
                                id = Uuid.parse(node.id),
                                messages = messages,
                                selectIndex = node.selectIndex.coerceIn(
                                    0,
                                    (messages.size - 1).coerceAtLeast(0)
                                ),
                            )
                        }.onFailure {
                            skippedNodes++
                            errors += "node:${node.id}:${it.message ?: "decode failed"}"
                        }.getOrNull()
                    }.filter { it.messages.isNotEmpty() }

                    val migratedNodes = parsedNodes.migrateToolNodes(
                        getMessages = { it.messages },
                        setMessages = { node, messages -> node.copy(messages = messages) },
                    )
                    require(migratedNodes.isNotEmpty()) { "conversation has no readable nodes" }

                    Conversation(
                        id = Uuid.parse(source.id),
                        assistantId = source.assistantId
                            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                            ?: assistantId,
                        title = source.title,
                        messageNodes = migratedNodes,
                        chatSuggestions = source.chatSuggestions,
                        isPinned = source.isPinned,
                        createAt = Instant.ofEpochMilli(source.createAt),
                        updateAt = Instant.ofEpochMilli(source.updateAt),
                        customSystemPrompt = source.customSystemPrompt,
                    )
                }.onFailure {
                    skippedConversations++
                    errors += "conversation:${source.sourceId ?: source.id}:${it.message ?: "decode failed"}"
                }.getOrNull()
            }

            return RikkaHubTransferImportResult(
                manifest = manifest,
                conversations = conversations,
                skippedConversations = skippedConversations,
                skippedNodes = skippedNodes,
                parsedMessages = parsedMessages,
                warnings = (manifest.warnings + diagnostics?.warnings.orEmpty()).distinct(),
                errors = (diagnostics?.errors.orEmpty() + errors).distinct(),
                attachments = attachments,
                settingsJson = settingsJson,
                files = files,
            )
        }
    }

    private fun decodeMessages(messages: JsonArray): List<UIMessage> {
        val raw = JsonInstant.encodeToString<JsonElement>(messages)
        val migrated = migrateMessagesJson(raw)
        return JsonInstant.decodeFromString<List<UIMessage>>(migrated)
    }

    private inline fun <reified T> ZipFile.readJsonEntry(name: String): T {
        val entry = getEntry(name) ?: error("Missing transfer entry: $name")
        return getInputStream(entry).bufferedReader().use { reader ->
            JsonInstant.decodeFromString(reader.readText())
        }
    }

    private inline fun <reified T> ZipFile.readJsonEntryOrNull(name: String): T? {
        val entry = getEntry(name) ?: return null
        return runCatching {
            getInputStream(entry).bufferedReader().use { reader ->
                JsonInstant.decodeFromString<T>(reader.readText())
            }
        }.getOrNull()
    }
}
