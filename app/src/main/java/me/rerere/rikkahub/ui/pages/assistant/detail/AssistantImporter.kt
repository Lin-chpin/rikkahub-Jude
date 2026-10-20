package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.R

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onImport: (Assistant, Lorebook?) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onImport)
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (Assistant, Lorebook?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

// region Parsing Strategy

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context, json: JsonObject): Assistant
}

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context, json: JsonObject): Assistant {
        val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        return createAssistant(data, name)
    }
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context, json: JsonObject): Assistant {
        val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull ?: error(context.getString(R.string.assistant_importer_missing_name_field))
        return createAssistant(data, name)
    }
}

internal fun createAssistant(data: JsonObject, name: String): Assistant {
    fun text(key: String) = data[key]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        .let { cleanTavernCardText(it, name) }

    val alternateGreetings = (data["alternate_greetings"] as? JsonArray).orEmpty()
        .mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
        .map { cleanTavernCardText(it, name) }
        .filter(String::isNotBlank)
    val firstMessage = text("first_mes").takeUnless { it == "系统：展示开局预览" }
    val greetings = (listOfNotNull(firstMessage?.takeIf(String::isNotBlank)) + alternateGreetings)
        .distinct()
    val depthPrompt = data["extensions"]?.jsonObject?.get("depth_prompt")?.jsonObject
        ?.get("prompt")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        .let { cleanTavernCardText(it, name) }
    val prompt = buildString {
        appendLine("You are roleplaying as $name.")
        listOf(
            "System prompt" to text("system_prompt"),
            "Character description" to text("description"),
            "Personality" to text("personality"),
            "Scenario" to text("scenario"),
            "Example dialogue" to text("mes_example"),
            "Post-history instructions" to text("post_history_instructions"),
            "Depth prompt" to depthPrompt,
        ).forEach { (title, content) ->
            if (content.isNotBlank()) {
                appendLine()
                appendLine("## $title")
                appendLine(content)
            }
        }
    }
    return Assistant(
        name = name,
        presetMessages = greetings.firstOrNull()?.let { listOf(UIMessage.assistant(it)) }.orEmpty(),
        alternateGreetings = greetings.drop(1),
        systemPrompt = prompt,
    )
}

internal fun cleanTavernCardText(value: String, characterName: String): String = value
    .replace(Regex("<%.*?%>", setOf(RegexOption.DOT_MATCHES_ALL)), "")
    .replace(Regex("<script\\b[^>]*>.*?</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    .replace(Regex("<style\\b[^>]*>.*?</style\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    .replace(Regex("```(?:html|css|javascript|js)\\b.*?```", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    .replace(Regex("""\{\{\s*char\s*\}\}""", RegexOption.IGNORE_CASE), characterName)
    .replace(Regex("""\{\{\s*user\s*\}\}""", RegexOption.IGNORE_CASE), "用户")
    .replace(Regex("""\{\{[^{}]*\}\}"""), "")
    .replace(Regex("</?[A-Za-z][^>]*>"), "")
    .trim()

internal data class ImportedTavernCard(
    val assistant: Assistant,
    val lorebook: Lorebook?,
)

internal fun attachCharacterBook(assistant: Assistant, lorebook: Lorebook?): ImportedTavernCard {
    val sourceLorebook = lorebook ?: return ImportedTavernCard(assistant, null)
    val characterDescriptionEntries = sourceLorebook.entries.filter { entry ->
        entry.enabled &&
            entry.constantActive &&
            entry.position == InjectionPosition.AFTER_SYSTEM_PROMPT &&
            entry.role == MessageRole.SYSTEM &&
            entry.keywords.isEmpty() &&
            entry.modelConditionRegex == null
    }
    if (characterDescriptionEntries.isEmpty()) return ImportedTavernCard(assistant, sourceLorebook)

    val systemPrompt = buildString {
        append(assistant.systemPrompt)
        characterDescriptionEntries.forEach { entry ->
            appendLine()
            if (entry.name.isNotBlank()) appendLine("## ${entry.name}")
            appendLine(entry.content)
        }
    }
    val remainingLorebook = sourceLorebook.copy(entries = sourceLorebook.entries - characterDescriptionEntries.toSet())
        .takeIf { it.entries.isNotEmpty() }
    return ImportedTavernCard(assistant.copy(systemPrompt = systemPrompt), remainingLorebook)
}

private val TAVERN_PARSERS: Map<String, TavernCardParser> = listOf(
    CharaCardV2Parser(),
    CharaCardV3Parser()
).associateBy { it.specName }

private fun parseAssistantFromJson(
    context: Context,
    json: JsonObject,
): ImportedTavernCard {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_spec_field))
    val parser = TAVERN_PARSERS[spec] ?: error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    val assistant = parser.parse(context = context, json = json)
    val data = json["data"]?.jsonObject
    val lorebook = (data?.get("character_book") as? JsonObject)?.let { book ->
        LorebookSerializer.tryImportSillyTavernCharacterBook(book, assistant.name)?.let { imported ->
            imported.copy(
                name = cleanTavernCardText(imported.name, assistant.name),
                description = cleanTavernCardText(imported.description, assistant.name),
                entries = imported.entries.mapNotNull { entry ->
                    val content = cleanTavernCardText(entry.content, assistant.name)
                    content.takeIf(String::isNotBlank)?.let {
                        entry.copy(
                            name = cleanTavernCardText(entry.name, assistant.name),
                            content = it,
                        )
                    }
                },
            ).takeIf { it.entries.isNotEmpty() }
        }
    }
    return attachCharacterBook(assistant, lorebook)
}

// endregion

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (Assistant, Lorebook?) -> Unit,
    toaster: ToasterState,
) {
    try {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val jsonString = withContext(Dispatchers.IO) {
            when (mime) {
                "image/png" -> {
                    val result = ImageUtils.getTavernCharacterMeta(context, uri)
                    result.map { base64Data -> String(Base64.decode(base64Data, Base64.DEFAULT)) }
                        .getOrElse { throw it }
                }

                "application/json" -> {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                        .use { it?.readText() }
                        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                    json
                }

                else -> error(context.getString(R.string.assistant_importer_unsupported_file_type, mime))
            }
        }
        val json = Json.parseToJsonElement(jsonString).jsonObject
        val imported = parseAssistantFromJson(context = context, json = json)
        onImport(imported.assistant, imported.lorebook)
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
            type = ToastType.Error
        )
    }
}
