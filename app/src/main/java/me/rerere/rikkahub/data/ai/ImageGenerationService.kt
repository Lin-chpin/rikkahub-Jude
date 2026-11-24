package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.withCodexImageGenerationCapability
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel

internal data class ImageGenerationTarget(
    val providerSetting: ProviderSetting,
    val model: Model,
    val customBody: List<CustomBody> = emptyList(),
)

class ImageGenerationUnavailableException(message: String) : IllegalStateException(message)

internal fun resolveImageGenerationModel(
    model: Model,
    providerSetting: ProviderSetting,
): Model = if (providerSetting is ProviderSetting.OpenAI) {
    model.withCodexImageGenerationCapability(providerSetting)
} else {
    model
}

class ImageGenerationService(
    private val providerManager: ProviderManager,
) {
    internal fun resolveTarget(
        settings: Settings,
        chatModelOverride: Model? = settings.getCurrentChatModel(),
    ): ImageGenerationTarget {
        val imageApi = settings.imageGenerationOpenAIConfig
        if (imageApi.enabled) {
            val modelId = imageApi.modelId.trim()
            if (modelId.isBlank()) {
                throw ImageGenerationUnavailableException("请在模型设置中填写生图 API 模型名称。")
            }
            val baseUrl = imageApi.baseUrl.trim().trimEnd('/')
            if (baseUrl.isBlank()) {
                throw ImageGenerationUnavailableException("请在模型设置中填写生图 API 地址。")
            }
            val model = Model(
                modelId = modelId,
                displayName = modelId,
                type = ModelType.IMAGE,
            )
            val size = imageApi.size.trim().ifBlank {
                if (modelId.equals("brain/januspro", ignoreCase = true)) "384x384" else ""
            }
            val customBody = size.takeIf(String::isNotBlank)?.let {
                listOf(CustomBody(key = "size", value = JsonPrimitive(it)))
            }.orEmpty()
            return ImageGenerationTarget(
                providerSetting = ProviderSetting.OpenAI(
                    name = "Image generation API",
                    apiKey = imageApi.apiKey,
                    baseUrl = baseUrl,
                ),
                model = model,
                customBody = customBody,
            )
        }

        val selectedModel = settings.findModelById(settings.imageGenerationModelId)
            ?: chatModelOverride
            ?: throw ImageGenerationUnavailableException(
                "没有配置独立生图模型，而且当前聊天模型也不可用。请在模型设置中配置生图模型。"
            )
        val providerSetting = selectedModel.findProvider(settings.providers)
            ?: throw ImageGenerationUnavailableException("所选生图模型的服务商不可用。")
        val model = resolveImageGenerationModel(selectedModel, providerSetting)
        if (!model.canGenerateImages(providerSetting)) {
            throw ImageGenerationUnavailableException(
                "没有配置可用的生图模型，而且当前聊天模型不支持生图。请在模型设置中配置生图模型，或选择支持生图的聊天模型。"
            )
        }
        return ImageGenerationTarget(providerSetting = providerSetting, model = model)
    }

    internal suspend fun generate(
        target: ImageGenerationTarget,
        params: ImageGenerationParams,
    ): ImageGenerationResult {
        val provider = providerManager.getProviderByType(target.providerSetting)
        if (target.model.type == ModelType.CHAT) {
            val aspectRatio = when (params.aspectRatio) {
                ImageAspectRatio.SQUARE -> "1:1"
                ImageAspectRatio.LANDSCAPE -> "16:9"
                ImageAspectRatio.PORTRAIT -> "9:16"
            }
            val customBody = params.customBody + if (target.providerSetting is ProviderSetting.Google) {
                listOf(
                    CustomBody(
                        key = "generationConfig",
                        value = buildJsonObject {
                            putJsonObject("imageConfig") { put("aspectRatio", aspectRatio) }
                        },
                    ),
                )
            } else {
                emptyList()
            }
            val response = provider.generateText(
                target.providerSetting,
                listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(params.prompt)))),
                TextGenerationParams(
                    model = target.model,
                    customHeaders = params.customHeaders,
                    customBody = customBody,
                ),
            )
            val images = response.choices
                .flatMap { it.message?.parts.orEmpty() }
                .filterIsInstance<UIMessagePart.Image>()
                .mapNotNull(::parseInlineImage)
                .take(params.numOfImages)
            if (images.isEmpty()) {
                error("所选聊天模型没有返回图片，请选择支持生图的模型。")
            }
            return ImageGenerationResult(images)
        }

        return provider.generateImage(target.providerSetting, params)
    }

    internal suspend fun edit(
        target: ImageGenerationTarget,
        params: me.rerere.ai.provider.ImageEditParams,
    ) = providerManager.getProviderByType(target.providerSetting)
        .editImage(target.providerSetting, params)

    private fun Model.canGenerateImages(providerSetting: ProviderSetting): Boolean = when (type) {
        ModelType.IMAGE -> true
        ModelType.CHAT -> Modality.IMAGE in outputModalities ||
            (BuiltInTools.ImageGeneration in tools &&
                providerSetting is ProviderSetting.OpenAI && providerSetting.useResponseApi)
        ModelType.EMBEDDING -> false
    }

    private fun parseInlineImage(image: UIMessagePart.Image): ImageGenerationItem? {
        val dataUrl = Regex("^data:([^;,]+);base64,(.*)$", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(image.url)
        if (dataUrl != null) {
            return ImageGenerationItem(
                data = dataUrl.groupValues[2],
                mimeType = dataUrl.groupValues[1],
            )
        }
        if (image.url.startsWith("data:", ignoreCase = true) || image.url.startsWith("http", ignoreCase = true)) {
            return null
        }
        return image.url.takeIf(String::isNotBlank)?.let {
            ImageGenerationItem(data = it, mimeType = "image/png")
        }
    }
}
