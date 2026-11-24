package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ImageGenerationService
import me.rerere.rikkahub.data.ai.ImageGenerationUnavailableException
import me.rerere.rikkahub.data.ai.ToolExecutionException
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import java.util.Base64

internal const val IMAGE_GENERATION_TOOL_NAME = "generate_image"

internal fun createImageGenerationTool(
    settings: Settings,
    chatModel: Model,
    imageGenerationService: ImageGenerationService,
    filesManager: FilesManager,
): Tool = Tool(
    name = IMAGE_GENERATION_TOOL_NAME,
    description = "Create an image from a text prompt using the configured image-generation model.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "A detailed description of the image to create")
                })
                put("aspect_ratio", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("square")
                        add("landscape")
                        add("portrait")
                    })
                    put("description", "Image shape: square, landscape, or portrait")
                })
                put("number_of_images", buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 4)
                    put("description", "How many image variations to create")
                })
            },
            required = listOf("prompt"),
        )
    },
    systemPrompt = { _, _ ->
        """
            When the user explicitly asks you to create, draw, or generate an image, call `$IMAGE_GENERATION_TOOL_NAME` with the image description. Do not call it for requests to analyze or describe an existing image. If the tool reports that image generation is unavailable or fails, tell the user clearly and do not claim an image was created.
        """.trimIndent()
    },
    execute = { arguments ->
        val args = arguments.asObject()
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw ToolExecutionException("argument_parsing", "invalid_arguments")
        val aspectRatio = when (args["aspect_ratio"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "landscape", "16:9" -> ImageAspectRatio.LANDSCAPE
            "portrait", "9:16" -> ImageAspectRatio.PORTRAIT
            else -> ImageAspectRatio.SQUARE
        }
        val numberOfImages = args["number_of_images"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(1, 4) ?: 1
        try {
            val target = imageGenerationService.resolveTarget(settings, chatModel)
            val result = imageGenerationService.generate(
                target = target,
                params = ImageGenerationParams(
                    model = target.model,
                    prompt = prompt,
                    numOfImages = numberOfImages,
                    aspectRatio = aspectRatio,
                    customHeaders = target.model.customHeaders,
                    customBody = target.model.customBodies + target.customBody,
                ),
            )
            val images = result.items.mapNotNull { item ->
                val rawBase64 = item.data.substringAfter("base64,", item.data)
                val bytes = runCatching { Base64.getDecoder().decode(rawBase64) }.getOrNull()
                    ?: return@mapNotNull null
                val mimeType = item.mimeType.takeIf(String::isNotBlank) ?: "image/png"
                val extension = mimeType.substringAfter('/', "png")
                    .replace("jpeg", "jpg", ignoreCase = true)
                    .filter(Char::isLetterOrDigit)
                    .ifBlank { "png" }
                filesManager.createChatFilesByByteArrays(
                    byteArrays = listOf(bytes),
                    displayName = "generated_image.$extension",
                    mimeType = mimeType,
                ).singleOrNull()?.let { uri -> UIMessagePart.Image(uri.toString()) }
            }
            if (images.isEmpty()) error("生图服务没有返回可用图片。")
            listOf(UIMessagePart.Text("图片已生成。")) + images
        } catch (error: ImageGenerationUnavailableException) {
            throw ToolExecutionException("configuration", "image_generation_unavailable", error)
        }
    },
)

private fun JsonElement.asObject(): JsonObject = try {
    jsonObject
} catch (_: IllegalStateException) {
    throw ToolExecutionException("argument_parsing", "invalid_arguments")
}
