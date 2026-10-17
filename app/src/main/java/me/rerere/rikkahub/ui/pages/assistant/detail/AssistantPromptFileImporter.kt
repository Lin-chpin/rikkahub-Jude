package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocParser
import me.rerere.document.DocxParser
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import java.io.File

internal data class ImportedPromptFile(
    val name: String,
    val text: String,
)

internal suspend fun importAssistantPromptFile(context: Context, uri: Uri): ImportedPromptFile =
    withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "imported-file"
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        val extension = name.substringAfterLast('.', "").lowercase()
        val kind = when {
            extension == "docx" || mime == DOCX_MIME -> PromptFileKind.DOCX
            extension == "doc" || mime == DOC_MIME -> PromptFileKind.DOC
            mime.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "webp", "heic", "heif", "gif", "bmp") -> PromptFileKind.IMAGE
            mime.startsWith("text/") || mime == "application/json" ||
                extension in setOf("txt", "md", "json", "csv", "yaml", "yml") -> PromptFileKind.TEXT
            else -> error("仅支持文本、图片、.docx 和 .doc 文件")
        }
        val suffix = extension.takeIf { it.length in 2..10 }?.let { ".$it" } ?: ".tmp"
        val tempFile = File.createTempFile("assistant-prompt-", suffix, context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use(input::copyTo)
            } ?: error("无法读取所选文件")

            val text = when (kind) {
                PromptFileKind.TEXT -> tempFile.readText(Charsets.UTF_8).also {
                    check(it.none { character -> character.isISOControl() && character !in "\n\r\t" }) { "文件包含非文本内容" }
                }
                PromptFileKind.DOC -> DocParser.parse(tempFile).also {
                    check(!it.startsWith("Error", ignoreCase = true)) { "DOC 文件解析失败" }
                }

                PromptFileKind.DOCX -> DocxParser.parse(tempFile).also {
                    check(!it.startsWith("Error", ignoreCase = true) &&
                        !it.startsWith("Unable", ignoreCase = true)
                    ) { "DOCX 文件解析失败" }
                }

                PromptFileKind.IMAGE -> OcrTransformer.performOcr(
                    UIMessagePart.Image(tempFile.toUri().toString()),
                ).also {
                    check(!it.startsWith("[ERROR", ignoreCase = true)) { "图片 OCR 失败，请检查 OCR 模型配置" }
                }
            }.trim()
            check(text.isNotBlank()) { "文件中没有可导入的文字" }
            Log.i("AssistantPromptImport", "imported type=$kind bytes=${tempFile.length()} characters=${text.length}")
            ImportedPromptFile(name, text)
        } finally {
            tempFile.delete()
        }
    }

private enum class PromptFileKind { TEXT, DOC, DOCX, IMAGE }

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val DOC_MIME = "application/msword"
