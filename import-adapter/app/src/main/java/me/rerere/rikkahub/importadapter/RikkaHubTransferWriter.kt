package me.rerere.rikkahub.importadapter

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.net.URLConnection
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ConversionSummary(
    val conversationCount: Int,
    val warningCount: Int,
    val errorCount: Int,
)

private data class StagedFile(
    val path: String,
    val file: File,
)

private data class StagedBackup(
    val databaseFile: File,
    val files: List<StagedFile>,
)

private fun stableImportUuid(value: String): String = UUID.nameUUIDFromBytes(
    value.toByteArray(StandardCharsets.UTF_8)
).toString()

private data class AttachmentSource(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val file: File? = null,
    val bytes: ByteArray? = null,
)

private class AttachmentCollector(
    private val stagedFiles: List<StagedFile>,
    private val warnings: MutableList<String>,
) {
    val attachments = linkedMapOf<String, AttachmentSource>()

    fun rewrite(url: String, type: String, fileNameHint: String?): String {
        if (url.isBlank()) return url
        if (url.startsWith("data:")) {
            return registerDataUrl(url, type, fileNameHint) ?: url
        }
        if (url.startsWith("http://") || url.startsWith("https://")) return url

        val sourcePath = Uri.parse(url).path ?: url
        val normalizedSource = normalize(sourcePath)
        val exact = stagedFiles.firstOrNull {
            normalize(it.path).endsWith(normalizedSource)
        }
        val baseName = normalizedSource.substringAfterLast('/')
        val byName = stagedFiles.filter {
            normalize(it.path).substringAfterLast('/') == baseName
        }
        val source = exact ?: byName.singleOrNull()
        if (source == null) {
            warnings += "unresolved attachment reference: $url"
            return url
        }
        return registerFile(
            file = source.file,
            fileName = fileNameHint?.takeIf { it.isNotBlank() } ?: source.file.name,
            mimeType = URLConnection.guessContentTypeFromName(source.file.name)
                ?: "application/octet-stream",
            identity = source.path,
        )
    }

    private fun registerDataUrl(url: String, type: String, fileNameHint: String?): String? {
        val separator = url.indexOf(',')
        if (separator < 0) {
            warnings += "invalid data attachment"
            return null
        }
        return runCatching {
            val header = url.substring(0, separator)
            val mimeType = header.substringAfter("data:").substringBefore(';')
                .ifBlank { defaultMimeType(type) }
            val payload = url.substring(separator + 1)
            val bytes = if (header.contains(";base64", true)) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                payload.toByteArray(StandardCharsets.UTF_8)
            }
            val extension = mimeType.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "bin"
            registerBytes(
                bytes = bytes,
                fileName = fileNameHint?.takeIf { it.isNotBlank() } ?: "imported.$extension",
                mimeType = mimeType,
                identity = url.hashCode().toString(),
            )
        }.getOrElse {
            warnings += "invalid data attachment: ${it.message ?: "decode failed"}"
            null
        }
    }

    private fun registerFile(file: File, fileName: String, mimeType: String, identity: String): String {
        return register(
            AttachmentSource(
                id = stableImportUuid("attachment:$identity"),
                fileName = File(fileName).name,
                mimeType = mimeType,
                file = file,
            )
        )
    }

    private fun registerBytes(bytes: ByteArray, fileName: String, mimeType: String, identity: String): String {
        return register(
            AttachmentSource(
                id = stableImportUuid("attachment:$identity"),
                fileName = File(fileName).name,
                mimeType = mimeType,
                bytes = bytes,
            )
        )
    }

    private fun register(source: AttachmentSource): String {
        attachments.putIfAbsent(source.id, source)
        return "rhk://attachment/${source.id}"
    }

    private fun defaultMimeType(type: String): String = when (type) {
        "image" -> "image/*"
        "video" -> "video/*"
        "audio" -> "audio/*"
        else -> "application/octet-stream"
    }

    private fun normalize(path: String): String = path
        .replace('\\', '/')
        .trimStart('/')
        .lowercase()
}

object RikkaHubTransferWriter {
    private const val FORMAT = "rikkahub-transfer"
    private const val FORMAT_VERSION = 1

    fun convert(input: File, output: File): ConversionSummary {
        val staging = File.createTempFile("rikkahub-adapter-", "-dir", output.parentFile)
        staging.delete()
        staging.mkdirs()
        try {
            val stagedBackup = extractDatabase(input, staging)
            return SQLiteDatabase.openDatabase(
                stagedBackup.databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                val warnings = mutableListOf<String>()
                val errors = mutableListOf<String>()
                val attachments = AttachmentCollector(stagedBackup.files, warnings)
                val tables = readTables(database)
                val conversationTable = tables.firstOrNull { it.equals("conversationentity", true) }
                    ?: error("ConversationEntity table not found")
                val nodeTable = tables.firstOrNull { it.equals("message_node", true) }
                val nodesByConversation = if (nodeTable != null) {
                    readNodes(database, nodeTable, warnings)
                } else {
                    emptyMap()
                }
                val conversations = readConversations(
                    database = database,
                    table = conversationTable,
                    nodesByConversation = nodesByConversation,
                    warnings = warnings,
                    errors = errors,
                    attachments = attachments,
                )
                require(conversations.isNotEmpty()) { "No readable conversations found" }

                writePackage(
                    output = output,
                    databaseVersion = database.version,
                    tables = tables,
                    conversations = conversations,
                    warnings = warnings,
                    errors = errors,
                    attachments = attachments.attachments.values.toList(),
                )
                ConversionSummary(
                    conversationCount = conversations.size,
                    warningCount = warnings.distinct().size,
                    errorCount = errors.distinct().size,
                )
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private data class SourceNode(
        val id: String,
        val index: Int,
        val messages: String,
        val selectIndex: Int,
    )

    private fun readTables(database: SQLiteDatabase): List<String> {
        val result = mutableListOf<String>()
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0)
        }
        return result
    }

    private fun readNodes(
        database: SQLiteDatabase,
        table: String,
        warnings: MutableList<String>,
    ): Map<String, List<SourceNode>> {
        val columns = readColumns(database, table)
        val conversationIdColumn = columns.firstOrNull { it.equals("conversation_id", true) }
            ?: return emptyMap()
        val messagesColumn = columns.firstOrNull { it.equals("messages", true) }
            ?: return emptyMap()
        val idColumn = columns.firstOrNull { it.equals("id", true) } ?: "rowid"
        val indexColumn = columns.firstOrNull { it.equals("node_index", true) }
        val selectIndexColumn = columns.firstOrNull { it.equals("select_index", true) }
        val result = linkedMapOf<String, MutableList<SourceNode>>()
        database.query(table, null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val conversationId = cursor.string(conversationIdColumn) ?: continue
                val messages = cursor.string(messagesColumn) ?: continue
                val node = SourceNode(
                    id = cursor.string(idColumn) ?: "row-${cursor.position}",
                    index = cursor.int(indexColumn) ?: cursor.position,
                    messages = messages,
                    selectIndex = cursor.int(selectIndexColumn) ?: 0,
                )
                result.getOrPut(conversationId) { mutableListOf() } += node
            }
        }
        if (result.isEmpty()) warnings += "message_node table was present but contained no readable rows"
        return result.mapValues { (_, nodes) -> nodes.sortedBy { it.index } }
    }

    private fun readConversations(
        database: SQLiteDatabase,
        table: String,
        nodesByConversation: Map<String, List<SourceNode>>,
        warnings: MutableList<String>,
        errors: MutableList<String>,
        attachments: AttachmentCollector,
    ): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        database.query(table, null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.string("id") ?: run {
                    errors += "conversation row ${cursor.position} has no id"
                    continue
                }
                val nodes = nodesByConversation[sourceId].orEmpty().mapNotNull { node ->
                    normalizeNode(node, sourceId, warnings, errors, attachments)
                }.toMutableList()
                if (nodes.isEmpty()) {
                    val legacyNodes = cursor.string("nodes")
                    nodes += parseLegacyNodes(legacyNodes, sourceId, warnings, errors, attachments)
                }
                if (nodes.isEmpty()) {
                    warnings += "conversation:$sourceId has no readable messages"
                    continue
                }

                val now = System.currentTimeMillis()
                val createAt = normalizeTimestamp(cursor.long("create_at") ?: now)
                val updateAt = normalizeTimestamp(cursor.long("update_at") ?: createAt)
                result += JSONObject().apply {
                    put("id", stableUuid("conversation:$sourceId"))
                    put("source_id", sourceId)
                    put("title", cursor.string("title")?.takeIf { it.isNotBlank() } ?: sourceId)
                    put("create_at", createAt)
                    put("update_at", updateAt)
                    put("custom_system_prompt", cursor.string("custom_system_prompt"))
                    put("chat_suggestions", parseStringArray(cursor.string("suggestions")))
                    put("is_pinned", cursor.int("is_pinned") == 1)
                    put("message_nodes", JSONArray(nodes))
                }
            }
        }
        return result
    }

    private fun normalizeNode(
        node: SourceNode,
        conversationId: String,
        warnings: MutableList<String>,
        errors: MutableList<String>,
        attachments: AttachmentCollector,
    ): JSONObject? {
        return runCatching {
            val messages = normalizeMessages(node.messages, conversationId, node.id, warnings, attachments)
            if (messages.length() == 0) return@runCatching null
            JSONObject().apply {
                put("id", stableUuid("node:$conversationId:${node.id}"))
                put("select_index", node.selectIndex.coerceIn(0, messages.length() - 1))
                put("messages", messages)
            }
        }.onFailure {
            errors += "node:${node.id}:${it.message ?: "message decode failed"}"
        }.getOrNull()
    }

    private fun parseLegacyNodes(
        raw: String?,
        conversationId: String,
        warnings: MutableList<String>,
        errors: MutableList<String>,
        attachments: AttachmentCollector,
    ): List<JSONObject> {
        if (raw.isNullOrBlank()) return emptyList()
        val nodes = runCatching { JSONArray(raw) }.getOrElse {
            errors += "conversation:$conversationId:legacy nodes JSON is invalid"
            return emptyList()
        }
        return buildList {
            for (index in 0 until nodes.length()) {
                val node = nodes.optJSONObject(index) ?: continue
                val sourceNodeId = node.optString("id", "legacy-$index")
                val messages = node.optJSONArray("messages") ?: continue
                val normalized = normalizeMessages(
                    messages.toString(),
                    conversationId,
                    sourceNodeId,
                    warnings,
                    attachments,
                )
                if (normalized.length() == 0) continue
                add(JSONObject().apply {
                    put("id", stableUuid("node:$conversationId:$sourceNodeId"))
                    put(
                        "select_index",
                        node.optInt("selectIndex", node.optInt("select_index", 0))
                            .coerceIn(0, normalized.length() - 1)
                    )
                    put("messages", normalized)
                })
            }
        }
    }

    private fun normalizeMessages(
        raw: String,
        conversationId: String,
        nodeId: String,
        warnings: MutableList<String>,
        attachments: AttachmentCollector,
    ): JSONArray {
        val source = JSONArray(raw)
        val result = JSONArray()
        for (index in 0 until source.length()) {
            val message = source.optJSONObject(index)?.let { JSONObject(it.toString()) } ?: continue
            val sourceMessageId = message.optString("id", "message-$index")
            message.put(
                "id",
                if (isUuid(sourceMessageId)) {
                    sourceMessageId
                } else {
                    stableUuid("message:$conversationId:$nodeId:$sourceMessageId")
                }
            )
            if (message.has("role")) {
                message.put("role", normalizeRole(message.optString("role")))
            }
            message.optJSONArray("parts")?.let { parts ->
                normalizeParts(parts, warnings, attachments)
            }
            val modelId = message.optString("modelId", "")
            if (modelId.isNotBlank() && !isUuid(modelId)) message.remove("modelId")
            result.put(message)
        }
        return result
    }

    private fun normalizeParts(
        parts: JSONArray,
        warnings: MutableList<String>,
        attachments: AttachmentCollector,
    ) {
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val type = normalizePartType(part.optString("type"))
            if (type.isNotBlank()) part.put("type", type)
            if (type in setOf("image", "video", "audio", "document")) {
                val url = part.optString("url", "")
                val rewritten = attachments.rewrite(
                    url = url,
                    type = type,
                    fileNameHint = part.optString("fileName", "").takeIf { it.isNotBlank() },
                )
                if (rewritten != url) part.put("url", rewritten)
            }
            part.optJSONArray("output")?.let { normalizeParts(it, warnings, attachments) }
        }
    }

    private fun writePackage(
        output: File,
        databaseVersion: Int,
        tables: List<String>,
        conversations: List<JSONObject>,
        warnings: List<String>,
        errors: List<String>,
        attachments: List<AttachmentSource>,
    ) {
        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            val manifest = JSONObject().apply {
                put("format", FORMAT)
                put("format_version", FORMAT_VERSION)
                put("source_app", "RikkaHub")
                put("source_version", JSONObject.NULL)
                put("source_database_version", databaseVersion)
                put("conversation_count", conversations.size)
                put("attachment_count", attachments.size)
                put("attachments", JSONArray(attachments.map { attachment ->
                    JSONObject().apply {
                        put("id", attachment.id)
                        put("file_name", attachment.fileName)
                        put("mime_type", attachment.mimeType)
                        put("entry", "attachments/${attachment.id}")
                    }
                }))
                put("warnings", JSONArray(warnings.distinct()))
            }
            putEntry(zip, "manifest.json", manifest.toString(2))
            putEntry(zip, "conversations.json", JSONArray(conversations).toString())
            attachments.forEach { attachment ->
                val entryName = "attachments/${attachment.id}"
                if (attachment.file != null) {
                    putFileEntry(zip, entryName, attachment.file)
                } else {
                    putEntry(zip, entryName, attachment.bytes ?: ByteArray(0))
                }
            }
            val diagnostics = JSONObject().apply {
                put("database_version", databaseVersion)
                put("tables", JSONArray(tables))
                put("warnings", JSONArray(warnings.distinct()))
                put("errors", JSONArray(errors.distinct()))
            }
            putEntry(zip, "diagnostics.json", diagnostics.toString(2))
        }
    }

    private fun putEntry(zip: ZipOutputStream, name: String, content: String) {
        putEntry(zip, name, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun putEntry(zip: ZipOutputStream, name: String, content: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
        zip.closeEntry()
    }

    private fun putFileEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun extractDatabase(input: File, staging: File): StagedBackup {
        if (!isZip(input)) return StagedBackup(input, emptyList())
        var databaseFile: File? = null
        val stagedFiles = mutableListOf<StagedFile>()
        val stagingRoot = staging.canonicalFile
        ZipInputStream(FileInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val relativePath = entry.name.replace('\\', '/').trimStart('/')
                val target = File(staging, relativePath).canonicalFile
                if (!target.path.startsWith(stagingRoot.path + File.separator)) continue
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output -> zip.copyTo(output) }
                stagedFiles += StagedFile(relativePath, target)
                if (relativePath.substringAfterLast('/').equals("rikka_hub.db", true)) {
                    databaseFile = target
                }
            }
        }
        return StagedBackup(
            databaseFile = databaseFile ?: error("rikka_hub.db not found in selected backup"),
            files = stagedFiles,
        )
    }

    private fun isZip(file: File): Boolean = file.inputStream().use { input ->
        input.read() == 0x50 && input.read() == 0x4b
    }

    private fun readColumns(database: SQLiteDatabase, table: String): List<String> {
        val result = mutableListOf<String>()
        database.rawQuery("PRAGMA table_info(${quote(table)})", null).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(1)
        }
        return result
    }

    private fun quote(identifier: String): String = "`" + identifier.replace("`", "``") + "`"

    private fun stableUuid(value: String): String = UUID.nameUUIDFromBytes(
        value.toByteArray(StandardCharsets.UTF_8)
    ).toString()

    private fun normalizeRole(value: String): String = value.substringAfterLast('.').lowercase()

    private fun normalizePartType(value: String): String = when (value.substringAfterLast('.').lowercase()) {
        "text" -> "text"
        "image" -> "image"
        "video" -> "video"
        "audio" -> "audio"
        "document" -> "document"
        "reasoning" -> "reasoning"
        "search" -> "search"
        "toolcall" -> "tool_call"
        "toolresult" -> "tool_result"
        "tool" -> "tool"
        else -> value
    }

    private fun normalizeTimestamp(value: Long): Long = if (value in 1..100_000_000_000L) value * 1000 else value

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private fun parseStringArray(raw: String?): JSONArray {
        if (raw.isNullOrBlank()) return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun Cursor.string(name: String): String? {
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.int(name: String?): Int? {
        if (name == null) return null
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    private fun Cursor.long(name: String): Long? {
        val index = getColumnIndex(name)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }
}
