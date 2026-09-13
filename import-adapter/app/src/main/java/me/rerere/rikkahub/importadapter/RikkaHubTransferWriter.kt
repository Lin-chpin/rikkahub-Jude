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
    val sourceConversationCount: Int = conversationCount,
    val sourceNodeCount: Int = 0,
    val sourceMessageCount: Int = 0,
    val databaseVersion: Int = 0,
    val restoredFileCount: Int = 0,
    val settingsFound: Boolean = false,
    val diagnosticsText: String = "",
)

private data class StagedFile(
    val path: String,
    val file: File,
)

private data class StagedBackup(
    val databaseFile: File,
    val files: List<StagedFile>,
    val settingsFile: File?,
)

private val RESTORABLE_FOLDERS = setOf("upload", "images", "skills", "fonts")

private fun restorableRelativePath(path: String): String? {
    val normalized = path.replace('\\', '/').trimStart('/')
    val lower = normalized.lowercase()
    val candidate = when {
        "/files/" in lower -> normalized.substring(lower.indexOf("/files/") + "/files/".length)
        lower.startsWith("files/") -> normalized.substring("files/".length)
        else -> normalized
    }
    val folder = candidate.substringBefore('/').lowercase()
    val relativePath = candidate.substringAfter('/', "")
    if (folder !in RESTORABLE_FOLDERS || relativePath.isBlank()) return null
    if (candidate.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
    return "$folder/$relativePath"
}

private fun stableImportUuid(value: String): String = UUID.nameUUIDFromBytes(
    value.toByteArray(StandardCharsets.UTF_8)
).toString()

private data class AttachmentSource(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val file: File? = null,
    val bytes: ByteArray? = null,
    val sourceRelativePath: String? = null,
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
        val normalizedSource = normalize(relativeAttachmentPath(sourcePath))
        val exact = stagedFiles.firstOrNull {
            val candidate = normalize(it.path)
            candidate == normalizedSource || candidate.endsWith("/$normalizedSource")
        }
        val baseName = normalizedSource.substringAfterLast('/')
        val byName = stagedFiles.filter {
            normalize(it.path).substringAfterLast('/') == baseName
        }
        val source = exact ?: byName.singleOrNull()
        if (source == null) {
            warnings += "未找到附件引用：$url"
            return url
        }
        return registerFile(
            file = source.file,
            fileName = fileNameHint?.takeIf { it.isNotBlank() } ?: source.file.name,
            mimeType = URLConnection.guessContentTypeFromName(source.file.name)
                ?: "application/octet-stream",
            identity = source.path,
            sourceRelativePath = source.path,
        )
    }

    private fun registerDataUrl(url: String, type: String, fileNameHint: String?): String? {
        val separator = url.indexOf(',')
        if (separator < 0) {
            warnings += "无效的数据附件"
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
            warnings += "无效的数据附件：${it.message ?: "解码失败"}"
            null
        }
    }

    private fun registerFile(
        file: File,
        fileName: String,
        mimeType: String,
        identity: String,
        sourceRelativePath: String? = null,
    ): String {
        return register(
            AttachmentSource(
                id = stableImportUuid("attachment:$identity"),
                fileName = File(fileName).name,
                mimeType = mimeType,
                file = file,
                sourceRelativePath = sourceRelativePath?.replace('\\', '/')?.trimStart('/'),
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

    private fun relativeAttachmentPath(path: String): String {
        return restorableRelativePath(path)
            ?: path.replace('\\', '/').trimStart('/')
    }
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
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                val warnings = mutableListOf<String>()
                val errors = mutableListOf<String>()
                val restoredFiles = stagedBackup.files
                    .mapNotNull { file ->
                        restorableRelativePath(file.path)?.let { path ->
                            StagedFile(path = path, file = file.file)
                        }
                    }
                    .distinctBy { it.path }
                val attachments = AttachmentCollector(restoredFiles, warnings)
                if (stagedBackup.settingsFile == null) {
                    warnings += "未找到 settings.json，本次转换将按兼容模式仅导入聊天数据"
                }
                val tables = readTables(database)
                val conversationTable = tables.firstOrNull { it.equals("conversationentity", true) }
                    ?: error("未找到 ConversationEntity 聊天表")
                val nodeTable = tables.firstOrNull { it.equals("message_node", true) }
                val sourceConversationCount = readRowCount(database, conversationTable)
                val nodeReadResult = if (nodeTable != null) {
                    readNodes(database, nodeTable, warnings)
                } else {
                    NodeReadResult(emptyMap(), 0, 0)
                }
                val conversations = readConversations(
                    database = database,
                    table = conversationTable,
                    nodesByConversation = nodeReadResult.nodesByConversation,
                    warnings = warnings,
                    errors = errors,
                    attachments = attachments,
                )
                require(conversations.isNotEmpty()) {
                    "数据库中读取到 $sourceConversationCount 个对话，但没有可转换的聊天消息"
                }

                writePackage(
                    output = output,
                    databaseVersion = database.version,
                    tables = tables,
                    conversations = conversations,
                    warnings = warnings,
                    errors = errors,
                    attachments = attachments.attachments.values.toList(),
                    settingsFile = stagedBackup.settingsFile,
                    restoredFiles = restoredFiles,
                )
                val distinctWarnings = warnings.distinct()
                val distinctErrors = errors.distinct()
                ConversionSummary(
                    conversationCount = conversations.size,
                    warningCount = distinctWarnings.size,
                    errorCount = distinctErrors.size,
                    sourceConversationCount = sourceConversationCount,
                    sourceNodeCount = nodeReadResult.nodeCount,
                    sourceMessageCount = nodeReadResult.messageCount,
                    databaseVersion = database.version,
                    restoredFileCount = restoredFiles.size,
                    settingsFound = stagedBackup.settingsFile != null,
                    diagnosticsText = buildDiagnosticsText(
                        databaseVersion = database.version,
                        tables = tables,
                        sourceConversationCount = sourceConversationCount,
                        sourceNodeCount = nodeReadResult.nodeCount,
                        sourceMessageCount = nodeReadResult.messageCount,
                        convertedConversationCount = conversations.size,
                        restoredFileCount = restoredFiles.size,
                        settingsFound = stagedBackup.settingsFile != null,
                        warnings = distinctWarnings,
                        errors = distinctErrors,
                    ),
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

    private data class NodeReadResult(
        val nodesByConversation: Map<String, List<SourceNode>>,
        val nodeCount: Int,
        val messageCount: Int,
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
    ): NodeReadResult {
        val columns = readColumns(database, table)
        val conversationIdColumn = columns.firstOrNull { it.equals("conversation_id", true) }
            ?: return NodeReadResult(emptyMap(), 0, 0)
        val messagesColumn = columns.firstOrNull { it.equals("messages", true) }
            ?: return NodeReadResult(emptyMap(), 0, 0)
        val idColumn = columns.firstOrNull { it.equals("id", true) } ?: "rowid"
        val indexColumn = columns.firstOrNull { it.equals("node_index", true) }
        val selectIndexColumn = columns.firstOrNull { it.equals("select_index", true) }
        val result = linkedMapOf<String, MutableList<SourceNode>>()
        var messageCount = 0
        database.query(table, null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val conversationId = cursor.string(conversationIdColumn) ?: continue
                val messages = cursor.string(messagesColumn) ?: continue
                messageCount += runCatching { JSONArray(messages).length() }.getOrDefault(0)
                val node = SourceNode(
                    id = cursor.string(idColumn) ?: "row-${cursor.position}",
                    index = cursor.int(indexColumn) ?: cursor.position,
                    messages = messages,
                    selectIndex = cursor.int(selectIndexColumn) ?: 0,
                )
                result.getOrPut(conversationId) { mutableListOf() } += node
            }
        }
        if (result.isEmpty()) warnings += "message_node 表存在，但没有读取到可用消息节点"
        return NodeReadResult(
            nodesByConversation = result.mapValues { (_, nodes) -> nodes.sortedBy { it.index } },
            nodeCount = result.values.sumOf { it.size },
            messageCount = messageCount,
        )
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
                    errors += "第 ${cursor.position + 1} 条聊天记录缺少 id"
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
                    warnings += "聊天记录 $sourceId 没有可读取的消息"
                    continue
                }

                val now = System.currentTimeMillis()
                val createAt = normalizeTimestamp(cursor.long("create_at") ?: now)
                val updateAt = normalizeTimestamp(cursor.long("update_at") ?: createAt)
                result += JSONObject().apply {
                    put("id", stableUuid("conversation:$sourceId"))
                    put("source_id", sourceId)
                    cursor.string("assistant_id")?.takeIf(::isUuid)?.let { put("assistant_id", it) }
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
            errors += "消息节点 ${node.id}：${it.message ?: "消息解码失败"}"
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
            errors += "聊天记录 $conversationId 的旧版消息节点 JSON 无效"
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
        settingsFile: File?,
        restoredFiles: List<StagedFile>,
    ) {
        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            val manifest = JSONObject().apply {
                put("format", FORMAT)
                put("format_version", FORMAT_VERSION)
                put("source_app", "RikkaHub")
                put("source_version", JSONObject.NULL)
                put("complete_restore", settingsFile != null)
                put("source_database_version", databaseVersion)
                put("conversation_count", conversations.size)
                put("attachment_count", attachments.size)
                put("file_count", restoredFiles.size)
                put("attachments", JSONArray(attachments.map { attachment ->
                    JSONObject().apply {
                        put("id", attachment.id)
                        put("file_name", attachment.fileName)
                        put("mime_type", attachment.mimeType)
                        put("entry", "attachments/${attachment.id}")
                        attachment.sourceRelativePath?.let {
                            put("source_relative_path", it)
                        }
                    }
                }))
                put("files", JSONArray(restoredFiles.map { file ->
                    JSONObject().apply {
                        put("relative_path", file.path.replace('\\', '/').trimStart('/'))
                        put("display_name", file.file.name)
                        put(
                            "mime_type",
                            URLConnection.guessContentTypeFromName(file.file.name)
                                ?: "application/octet-stream"
                        )
                        put("entry", "files/${file.path.replace('\\', '/').trimStart('/')}")
                    }
                }))
                put("warnings", JSONArray(warnings.distinct()))
            }
            putEntry(zip, "manifest.json", manifest.toString(2))
            putEntry(zip, "conversations.json", JSONArray(conversations).toString())
            settingsFile?.let { putFileEntry(zip, "settings.json", it) }
            restoredFiles.forEach { file ->
                putFileEntry(
                    zip,
                    "files/${file.path.replace('\\', '/').trimStart('/')}",
                    file.file,
                )
            }
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

    private fun buildDiagnosticsText(
        databaseVersion: Int,
        tables: List<String>,
        sourceConversationCount: Int,
        sourceNodeCount: Int,
        sourceMessageCount: Int,
        convertedConversationCount: Int,
        restoredFileCount: Int,
        settingsFound: Boolean,
        warnings: List<String>,
        errors: List<String>,
    ): String = buildString {
        appendLine("诊断信息")
        appendLine("数据库版本：$databaseVersion")
        appendLine("数据库表：${tables.joinToString(", ")}")
        appendLine("源聊天数：$sourceConversationCount")
        appendLine("源消息节点数：$sourceNodeCount")
        appendLine("源消息数：$sourceMessageCount")
        appendLine("成功转换聊天数：$convertedConversationCount")
        appendLine("恢复文件数：$restoredFileCount")
        appendLine("设置文件：${if (settingsFound) "已找到" else "未找到（兼容模式）"}")
        appendLine("警告数：${warnings.size}")
        warnings.forEach { appendLine("警告：$it") }
        appendLine("错误数：${errors.size}")
        errors.forEach { appendLine("错误：$it") }
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
        if (!isZip(input)) {
            val databaseFile = File(staging, "rikka_hub.db")
            FileInputStream(input).use { source ->
                FileOutputStream(databaseFile).use { target -> source.copyTo(target) }
            }
            return StagedBackup(
                databaseFile = databaseFile,
                files = listOf(StagedFile("rikka_hub.db", databaseFile)),
                settingsFile = null,
            )
        }
        var databaseFile: File? = null
        var settingsFile: File? = null
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
                if (relativePath.substringAfterLast('/').equals("settings.json", true)) {
                    settingsFile = target
                }
            }
        }
        return StagedBackup(
            databaseFile = databaseFile ?: error("所选备份中未找到 rikka_hub.db 聊天数据库"),
            files = stagedFiles,
            settingsFile = settingsFile,
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

    private fun readRowCount(database: SQLiteDatabase, table: String): Int {
        return database.rawQuery("SELECT COUNT(*) FROM ${quote(table)}", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
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
