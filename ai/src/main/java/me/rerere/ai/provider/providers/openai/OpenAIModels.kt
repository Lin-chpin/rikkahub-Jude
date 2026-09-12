package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val CODEX_MODELS_CLIENT_VERSION = "0.148.0"

internal fun openAIModelsUrl(providerSetting: ProviderSetting.OpenAI): HttpUrl =
    "${providerSetting.baseUrl.trimEnd('/')}/models"
        .toHttpUrl()
        .newBuilder()
        .apply {
            if (providerSetting.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) {
                addQueryParameter("client_version", CODEX_MODELS_CLIENT_VERSION)
            }
        }
        .build()

internal fun parseOpenAIModels(body: String): List<Model> {
    val bodyJson = json.parseToJsonElement(body).jsonObject
    val data = bodyJson["data"]?.jsonArray
        ?: bodyJson["models"]?.jsonArray
        ?: return emptyList()

    return data.mapNotNull { modelJson ->
        val modelObj = modelJson.jsonObject
        if (modelObj["supported_in_api"]?.jsonPrimitive?.booleanOrNull == false) {
            return@mapNotNull null
        }
        if (modelObj["visibility"]?.jsonPrimitive?.contentOrNull == "hide") {
            return@mapNotNull null
        }
        val id = modelObj["id"]?.jsonPrimitive?.contentOrNull
            ?: modelObj["slug"]?.jsonPrimitive?.contentOrNull
            ?: modelObj["model"]?.jsonPrimitive?.contentOrNull
            ?: return@mapNotNull null
        val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id
        Model(modelId = id, displayName = displayName)
    }
}
