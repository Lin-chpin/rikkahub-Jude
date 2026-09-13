package me.rerere.rikkahub.data.sync.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

const val RIKKAHUB_TRANSFER_FORMAT = "rikkahub-transfer"
const val RIKKAHUB_TRANSFER_FORMAT_VERSION = 1

@Serializable
data class RikkaHubTransferManifest(
    val format: String,
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("source_app") val sourceApp: String,
    @SerialName("source_version") val sourceVersion: String? = null,
    @SerialName("complete_restore") val completeRestore: Boolean = false,
    @SerialName("conversation_count") val conversationCount: Int = 0,
    @SerialName("attachment_count") val attachmentCount: Int = 0,
    @SerialName("file_count") val fileCount: Int = 0,
    val attachments: List<RikkaHubTransferAttachment> = emptyList(),
    val files: List<RikkaHubTransferFile> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class RikkaHubTransferDiagnostics(
    @SerialName("database_version") val databaseVersion: Int? = null,
    val tables: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

@Serializable
data class RikkaHubTransferConversation(
    val id: String,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("assistant_id") val assistantId: String? = null,
    val title: String,
    @SerialName("create_at") val createAt: Long,
    @SerialName("update_at") val updateAt: Long,
    @SerialName("custom_system_prompt") val customSystemPrompt: String? = null,
    @SerialName("chat_suggestions") val chatSuggestions: List<String> = emptyList(),
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("message_nodes") val messageNodes: List<RikkaHubTransferMessageNode>,
)

@Serializable
data class RikkaHubTransferMessageNode(
    val id: String,
    @SerialName("select_index") val selectIndex: Int = 0,
    val messages: JsonArray,
)

@Serializable
data class RikkaHubTransferAttachment(
    val id: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("mime_type") val mimeType: String = "application/octet-stream",
    val entry: String,
    @SerialName("source_relative_path") val sourceRelativePath: String? = null,
)

@Serializable
data class RikkaHubTransferFile(
    @SerialName("relative_path") val relativePath: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("mime_type") val mimeType: String = "application/octet-stream",
    val entry: String,
)
