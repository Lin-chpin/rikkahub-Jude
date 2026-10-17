package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.document.DocParser
import me.rerere.document.DocxParser
import java.io.File
import java.time.LocalDateTime
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement
)

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(context: Context, uri: Uri): Result<T>

    // 获取导出文件名
    fun getExportFileName(data: T): String = "${type}.json"

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 读取 URI 内容的便捷方法
    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
    }

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

object ModeInjectionSerializer : ExportSerializer<PromptInjection.ModeInjection> {
    override val type = "mode_injection"

    override fun getExportFileName(data: PromptInjection.ModeInjection): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: PromptInjection.ModeInjection): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<PromptInjection.ModeInjection> {
        return runCatching {
            val fileName = getUriFileName(context, uri).orEmpty()
            val mimeType = context.contentResolver.getType(uri)
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val documentType = when {
                extension == "docx" || mimeType == DOCX_MIME_TYPE -> "docx"
                extension == "doc" || mimeType == DOC_MIME_TYPE -> "doc"
                else -> null
            }
            if (documentType != null) {
                importDocument(context, uri, fileName, documentType)
            } else {
                val json = readUri(context, uri)
                tryImportNative(json)
                    ?: throw IllegalArgumentException("Unsupported format")
            }
        }
    }

    private fun importDocument(
        context: Context,
        uri: Uri,
        fileName: String,
        documentType: String,
    ): PromptInjection.ModeInjection {
        val file = File.createTempFile("prompt-injection-", ".$documentType", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use(input::copyTo)
            } ?: error("Failed to read file")
            val content = when (documentType) {
                "doc" -> DocParser.parse(file)
                else -> DocxParser.parse(file).also {
                    require(!it.startsWith("Error parsing") && it != "Unable to find document content in DOCX file") {
                        it
                    }
                }
            }
            require(content.isNotBlank()) { "Document contains no text" }
            return PromptInjection.ModeInjection(
                name = fileName.substringBeforeLast('.', fileName),
                content = content,
            )
        } finally {
            file.delete()
        }
    }

    private fun tryImportNative(json: String): PromptInjection.ModeInjection? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
                .copy(id = Uuid.random())
        }.getOrNull()
    }

    private const val DOC_MIME_TYPE = "application/msword"
    private const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: Lorebook): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            val json = readUri(context, uri)
            val root = ExportSerializer.DefaultJson.parseToJsonElement(json)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
                ?: if (root is JsonObject && "prompts" in root) {
                    // SillyTavern prompt presets use prompts + prompt_order, unlike world-info books.
                    tryImportSillyTavernPreset(json, getUriFileName(context, uri)?.removeSuffix(".json"))
                        ?: throw IllegalArgumentException("Invalid SillyTavern preset")
                } else {
                    // 然后尝试解析为 SillyTavern 格式
                    tryImportSillyTavern(json, getUriFileName(context, uri)?.removeSuffix(".json"))
                }
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): Lorebook? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(exportData.data)
                .copy(
                    id = Uuid.random(),
                    entries = ExportSerializer.DefaultJson
                        .decodeFromJsonElement<Lorebook>(exportData.data)
                        .entries.map { it.copy(id = Uuid.random()) }
                )
        }.getOrNull()
    }

    private fun tryImportSillyTavern(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val stLorebook = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernLorebook.serializer(),
                json
            )
            Lorebook(
                id = Uuid.random(),
                name = fileName ?: LocalDateTime.now().toLocalString(),
                description = "",
                enabled = true,
                entries = stLorebook.entries.values.map { entry ->
                    PromptInjection.RegexInjection(
                        id = Uuid.random(),
                        name = entry.comment.orEmpty().ifEmpty { entry.key.firstOrNull().orEmpty() },
                        enabled = !entry.disable,
                        priority = entry.order,
                        position = mapSillyTavernPosition(entry.position),
                        injectDepth = entry.depth,
                        content = entry.content,
                        keywords = entry.key,
                        useRegex = false, // SillyTavern 格式不支持 useRegex
                        caseSensitive = entry.caseSensitive ?: false,
                        scanDepth = entry.scanDepth ?: 4,
                        constantActive = entry.constant,
                    )
                }
            )
        }.getOrNull()
    }

    internal fun tryImportSillyTavernPreset(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val preset = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernPreset.serializer(),
                json,
            )
            require(preset.prompts.isNotEmpty() && preset.promptOrder.isNotEmpty())
            val order = preset.promptOrder.maxByOrNull { it.order.size }?.order.orEmpty()
            val orderById = order.withIndex().associate { it.value.identifier to (it.index to it.value.enabled) }
            val historyIndex = order.indexOfFirst { it.identifier == "chatHistory" }
            val entryCount = order.size

            val entries = preset.prompts.mapNotNull { prompt ->
                val orderInfo = orderById[prompt.identifier]
                val orderIndex = orderInfo?.first ?: order.size
                val isOrderedAndEnabled = orderInfo?.second == true
                val marker = prompt.identifier == "charDescription" && prompt.content.isBlank()
                val content = when {
                    marker -> "{{assistant_system_prompt}}"
                    else -> prompt.content
                }
                if (content.isBlank() || prompt.identifier in IGNORED_PRESET_PROMPTS) return@mapNotNull null

                val condition = if (prompt.conditionEnabled) parseSimpleModelCondition(prompt.conditionScript) else null
                val supportedCondition = !prompt.conditionEnabled || condition != null
                val isInChat = prompt.injectionPosition == 1
                val isAfterHistory = historyIndex >= 0 && orderIndex > historyIndex
                val position = when {
                    isInChat -> InjectionPosition.AT_DEPTH
                    isAfterHistory -> InjectionPosition.AT_DEPTH
                    else -> InjectionPosition.AFTER_SYSTEM_PROMPT
                }
                val depth = when {
                    isInChat -> prompt.injectionDepth
                    isAfterHistory -> 0
                    else -> prompt.injectionDepth
                }
                val priority = if (isInChat) {
                    -prompt.injectionOrder
                } else {
                    entryCount - orderIndex
                }

                PromptInjection.RegexInjection(
                    id = Uuid.random(),
                    name = prompt.name.ifBlank { prompt.identifier },
                    enabled = prompt.enabled && isOrderedAndEnabled && supportedCondition,
                    priority = priority,
                    position = position,
                    content = content,
                    injectDepth = depth,
                    role = when (prompt.role.lowercase()) {
                        "assistant" -> MessageRole.ASSISTANT
                        "user" -> MessageRole.USER
                        else -> MessageRole.SYSTEM
                    },
                    constantActive = true,
                    modelConditionRegex = condition?.first,
                    modelConditionIgnoreCase = condition?.second == true,
                    preserveRole = true,
                    tavernPresetSlot = "charDescription".takeIf { marker },
                )
            }
            require(entries.isNotEmpty())
            Lorebook(
                id = Uuid.random(),
                name = fileName?.takeIf { it.isNotBlank() } ?: "SillyTavern Preset",
                description = "酒馆预设兼容导入：保留提示词顺序、角色、注入深度和助手输出正则。",
                enabled = true,
                entries = entries,
                responseRegexes = parseSillyTavernResponseRegexes(preset),
            )
        }.getOrNull()
    }

    private fun parseSillyTavernResponseRegexes(preset: SillyTavernPreset): List<AssistantRegex> =
        preset.extensions.sPreset?.regexBinding?.regexes.orEmpty().mapNotNull { rule ->
            if (rule.promptOnly || 2 !in rule.placement) return@mapNotNull null
            val parsed = parseSillyTavernRegex(rule.findRegex) ?: return@mapNotNull null
            val replacement = normalizeSillyTavernReplacement(rule.replaceString)
                ?: return@mapNotNull null
            if (runCatching { Regex(parsed.pattern) }.isFailure) return@mapNotNull null
            AssistantRegex(
                id = Uuid.random(),
                name = rule.scriptName,
                enabled = !rule.disabled,
                findRegex = parsed.pattern,
                replaceString = replacement,
                affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                visualOnly = true,
                replaceFirstOnly = !parsed.global,
            )
        }

    private fun parseSillyTavernRegex(source: String): ParsedTavernRegex? {
        val literal = Regex("""^/(.*)/([a-z]*)$""", RegexOption.DOT_MATCHES_ALL).matchEntire(source)
        val pattern = literal?.groupValues?.get(1) ?: source
        val flags = literal?.groupValues?.get(2).orEmpty()
        if (flags.toSet().size != flags.length || flags.any { it !in "gimsu" }) return null
        val inlineFlags = buildString {
            if ('i' in flags) append('i')
            if ('m' in flags) append('m')
            if ('s' in flags) append('s')
        }
        return ParsedTavernRegex(
            pattern = if (inlineFlags.isEmpty()) pattern else "(?$inlineFlags)$pattern",
            global = 'g' in flags,
        )
    }

    private fun normalizeSillyTavernReplacement(source: String): String? {
        if (Regex("""\$(?:`|')""").containsMatchIn(source)) return null
        return Regex("""\$(?:<([A-Za-z][A-Za-z0-9_]*)>|&|\$)""").replace(source) { match ->
            when {
                match.groupValues[1].isNotEmpty() -> "\${${match.groupValues[1]}}"
                match.value == "$&" -> "\$0"
                else -> "\\$"
            }
        }
    }

    private fun parseSimpleModelCondition(script: String): Pair<String, Boolean>? {
        val match = SIMPLE_MODEL_CONDITION.find(script) ?: return null
        val flags = match.groupValues[2]
        if (flags.any { it != 'i' }) return null
        return match.groupValues[1].replace("\\/", "/") to ('i' in flags)
    }

    private val SIMPLE_MODEL_CONDITION = Regex("""=>\s*/((?:\\.|[^/])*)/([a-z]*)\.test\(model\)""")
    private val IGNORED_PRESET_PROMPTS = setOf("SPresetSettings", "version_info")

    private fun mapSillyTavernPosition(position: Int): InjectionPosition {
        return when (position) {
            0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
            2 -> InjectionPosition.TOP_OF_CHAT
            3 -> InjectionPosition.TOP_OF_CHAT // After Examples -> 聊天历史开头
            4 -> InjectionPosition.AT_DEPTH    // @Depth 模式
            else -> InjectionPosition.AFTER_SYSTEM_PROMPT
        }
    }
}

@Serializable
private data class SillyTavernPreset(
    val prompts: List<SillyTavernPresetPrompt> = emptyList(),
    @SerialName("prompt_order") val promptOrder: List<SillyTavernPresetOrder> = emptyList(),
    val extensions: SillyTavernPresetExtensions = SillyTavernPresetExtensions(),
)

@Serializable
private data class SillyTavernPresetExtensions(
    @SerialName("SPreset") val sPreset: SillyTavernSPreset? = null,
)

@Serializable
private data class SillyTavernSPreset(
    @SerialName("RegexBinding") val regexBinding: SillyTavernRegexBinding? = null,
)

@Serializable
private data class SillyTavernRegexBinding(
    val regexes: List<SillyTavernResponseRegex> = emptyList(),
)

@Serializable
private data class SillyTavernResponseRegex(
    val scriptName: String = "",
    val findRegex: String = "",
    val replaceString: String = "",
    val disabled: Boolean = false,
    val placement: List<Int> = emptyList(),
    val promptOnly: Boolean = false,
)

private data class ParsedTavernRegex(
    val pattern: String,
    val global: Boolean,
)

@Serializable
private data class SillyTavernPresetPrompt(
    val identifier: String = "",
    val name: String = "",
    val content: String = "",
    val role: String = "system",
    val enabled: Boolean = true,
    @SerialName("injection_position") val injectionPosition: Int = 0,
    @SerialName("injection_depth") val injectionDepth: Int = 4,
    @SerialName("injection_order") val injectionOrder: Int = 100,
    @SerialName("spreset_condition_enabled") val conditionEnabled: Boolean = false,
    @SerialName("spreset_condition_script") val conditionScript: String = "",
)

@Serializable
private data class SillyTavernPresetOrder(
    @SerialName("character_id") val characterId: Int = 0,
    val order: List<SillyTavernPresetOrderItem> = emptyList(),
)

@Serializable
private data class SillyTavernPresetOrderItem(
    val identifier: String = "",
    val enabled: Boolean = true,
)

@Serializable
private data class SillyTavernLorebook(
    val entries: Map<String, SillyTavernEntry> = emptyMap(),
)

@Serializable
private data class SillyTavernEntry(
    val key: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val position: Int = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val depth: Int = 4,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
)
