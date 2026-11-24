package me.rerere.ai.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.merge
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderResponseParseException
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.safeProviderIdentifier
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.privateRequestFailure
import me.rerere.common.http.suppressContentLogs
import me.rerere.common.http.withoutAutomaticReplay
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

internal class GeminiOAuthProvider(
    private val client: OkHttpClient,
    private val tokenProvider: GeminiOAuthTokenProvider,
    private val googleWire: GoogleProvider,
) {
    suspend fun listModels(): List<Model> = withContext(Dispatchers.IO) {
        val credentials = tokenProvider.getCredentials()
        val response = postWithEndpointFallback(
            path = "v1internal:fetchAvailableModels",
            accessToken = credentials.accessToken,
            body = json.encodeToString(buildJsonObject {
                put("project", credentials.projectId)
            }),
            suppressLogs = true,
        )
        response.use {
            if (!it.isSuccessful) throw requestFailure(it)
            val root = json.parseToJsonElement(it.body.string()).jsonObject
            val models = root["models"] as? JsonObject ?: JsonObject(emptyMap())
            val imageModelIds = (root["imageGenerationModelIds"] ?: root["image_generation_model_ids"])
                ?.let { value ->
                    (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                }
                .orEmpty()
                .filterNot(::isHiddenModelId)
                .toSet()

            val chatModels = models.mapNotNull { (modelId, value) ->
                val model = value as? JsonObject ?: return@mapNotNull null
                if (isHiddenModel(modelId, model) || modelId in imageModelIds) {
                    return@mapNotNull null
                }
                val supportsImages = model["supportsImages"]?.jsonPrimitive?.booleanOrNull == true
                val abilities = buildList {
                    add(ModelAbility.TOOL)
                    if (
                        model["supportsThinking"]?.jsonPrimitive?.booleanOrNull == true &&
                        !modelId.contains("gpt-oss", ignoreCase = true)
                    ) {
                        add(ModelAbility.REASONING)
                    }
                }
                Model(
                    modelId = modelId,
                    displayName = model["displayName"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank) ?: modelId,
                    type = ModelType.CHAT,
                    inputModalities = if (supportsImages) {
                        listOf(Modality.TEXT, Modality.IMAGE)
                    } else {
                        listOf(Modality.TEXT)
                    },
                    abilities = abilities,
                )
            }

            val imageModels = imageModelIds.map { modelId ->
                val metadata = models[modelId] as? JsonObject
                Model(
                    modelId = modelId,
                    displayName = metadata?.get("displayName")?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank) ?: modelId,
                    type = ModelType.IMAGE,
                    inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                    outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                )
            }
            chatModels + imageModels
        }
    }

    suspend fun generateText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        var output = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        var usage = null as me.rerere.ai.core.TokenUsage?
        streamText(messages, params).collect { chunk ->
            output = output.handleMessageChunk(chunk, params.model)
            chunk.usage?.let { usage = usage.merge(it) }
        }
        val message = output.lastOrNull()
            ?.takeIf { it.parts.isNotEmpty() }
            ?.copy(modelId = params.model.id, usage = usage)
            ?: error("Gemini subscription returned no response")
        return MessageChunk(
            id = UUID.randomUUID().toString(),
            model = params.model.modelId,
            choices = listOf(UIMessageChoice(index = 0, delta = null, message = message, finishReason = null)),
            usage = usage,
        )
    }

    suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val credentials = tokenProvider.getCredentials()
        val request = googleWire.buildCompletionRequestBody(
            messages,
            params,
            CODE_ASSIST_SAFETY_CATEGORIES,
        ).withAntigravitySessionId(credentials.projectId, params.sessionId)
        val requestBody = buildJsonObject {
            put("project", credentials.projectId)
            put("model", params.model.modelId)
            put("request", request)
        }
        val body = json.encodeToString(requestBody)
        val outcome = runStreamAttempt(
            endpoint = GEMINI_DAILY_CODE_ASSIST_ENDPOINT,
            accessToken = credentials.accessToken,
            body = body,
            params = params,
            flowScope = this,
        )
        if (outcome.success) close() else close(outcome.failure ?: IOException("Gemini subscription request failed"))
        awaitClose { }
    }

    suspend fun generateImage(params: ImageGenerationParams): ImageGenerationResult = withContext(Dispatchers.IO) {
        require(params.numOfImages in 1..4) { "Gemini subscription supports requesting 1 to 4 images" }
        val credentials = tokenProvider.getCredentials()
        val items = buildList {
            repeat(params.numOfImages) {
                val nativeRequest = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", params.prompt) })
                            })
                        })
                    })
                    put("generationConfig", buildJsonObject {
                        putJsonArray("responseModalities") { add(JsonPrimitive("IMAGE")) }
                        putJsonObject("imageConfig") {
                            put(
                                "aspectRatio",
                                when (params.aspectRatio) {
                                    ImageAspectRatio.SQUARE -> "1:1"
                                    ImageAspectRatio.LANDSCAPE -> "16:9"
                                    ImageAspectRatio.PORTRAIT -> "9:16"
                                },
                            )
                        }
                    })
                }.mergeCustomBody(params.customBody)
                val envelope = buildJsonObject {
                    put("project", credentials.projectId)
                    put("model", params.model.modelId)
                    put("requestType", "image_gen")
                    put("userAgent", "antigravity")
                    put("requestId", "image_gen/${System.currentTimeMillis()}/${UUID.randomUUID()}/1")
                    put("request", nativeRequest)
                }
                val response = postWithEndpointFallback(
                    path = "v1internal:generateContent",
                    accessToken = credentials.accessToken,
                    body = json.encodeToString(envelope),
                    suppressLogs = true,
                    customHeaders = params.customHeaders.toHeaders(),
                    allowEndpointFallback = false,
                )
                response.use {
                    if (!it.isSuccessful) throw requestFailure(it)
                    val body = runCatching { json.parseToJsonElement(it.body.string()).jsonObject }
                        .getOrElse { error ->
                            throw ProviderResponseParseException(
                                "Google subscription image response could not be parsed",
                                error,
                            )
                        }
                    val nativeResponse = body["response"] as? JsonObject ?: body
                    addAll(parseGeneratedImages(nativeResponse))
                }
            }
        }
        if (items.isEmpty()) {
            throw ProviderResponseParseException(
                "Google subscription returned no image data",
                IllegalStateException("No inline image data was present in the response"),
            )
        }
        ImageGenerationResult(items)
    }

    private fun parseGeneratedImages(response: JsonObject): List<ImageGenerationItem> {
        val candidates = response["candidates"] as? JsonArray ?: return emptyList()
        return candidates.flatMap { candidate ->
            val parts = (candidate as? JsonObject)
                ?.get("content")?.jsonObject?.get("parts") as? JsonArray
                ?: return@flatMap emptyList()
            parts.mapNotNull { part ->
                val inlineData = (part as? JsonObject)?.let { partObject ->
                    (partObject["inlineData"] ?: partObject["inline_data"]) as? JsonObject
                } ?: return@mapNotNull null
                val data = inlineData["data"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                ImageGenerationItem(
                    data = data,
                    mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull
                        ?: inlineData["mime_type"]?.jsonPrimitive?.contentOrNull
                        ?: "image/png",
                )
            }
        }
    }

    private fun isHiddenModelId(modelId: String): Boolean =
        modelId.endsWith("-disabled", ignoreCase = true) ||
            modelId.endsWith("-sonar", ignoreCase = true)

    private fun isHiddenModel(modelId: String, model: JsonObject): Boolean =
        isHiddenModelId(modelId) ||
            model["isInternal"]?.jsonPrimitive?.booleanOrNull == true ||
            model["disabled"]?.jsonPrimitive?.booleanOrNull == true

    private suspend fun runStreamAttempt(
        endpoint: String,
        accessToken: String,
        body: String,
        params: TextGenerationParams,
        flowScope: ProducerScope<MessageChunk>,
    ): StreamAttempt = suspendCancellableCoroutine { continuation ->
        fun resumeOnce(result: StreamAttempt) {
            if (continuation.isActive) continuation.resume(result)
        }

        val request = Request.Builder()
            .url("$endpoint/v1internal:streamGenerateContent?alt=sse")
            .headers(params.customHeaders.toHeaders())
            .withGeminiAntigravityHeaders(accessToken)
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA_TYPE).withoutAutomaticReplay())
            .suppressContentLogs(params.suppressContentLogs)
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                try {
                    val payload = json.parseToJsonElement(data).jsonObject
                    val error = payload["error"]?.jsonObject
                    if (error != null) {
                        val status = error["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        val code = error["status"]?.jsonPrimitive?.contentOrNull
                        resumeOnce(
                            StreamAttempt(
                                success = false,
                                failure = HttpException(
                                    "Google Cloud Code Assist rejected the request",
                                    statusCode = status,
                                    providerCode = code?.safeProviderIdentifier(),
                                ),
                            )
                        )
                        eventSource.cancel()
                        return
                    }
                    val response = payload["response"]?.jsonObject ?: return
                    val blockReason = response["promptFeedback"]?.jsonObject
                        ?.get("blockReason")?.jsonPrimitive?.contentOrNull
                    if (blockReason != null) {
                        resumeOnce(
                            StreamAttempt(
                                success = false,
                                failure = HttpException(
                                    "Google blocked the request",
                                    providerCode = blockReason.safeProviderIdentifier(),
                                ),
                            )
                        )
                        eventSource.cancel()
                        return
                    }
                    val chunk = googleWire.parseCodeAssistResponse(response, params.model, streaming = true)
                        ?: return
                    flowScope.trySendBlocking(chunk).getOrThrow()
                } catch (error: Exception) {
                    resumeOnce(
                        StreamAttempt(
                            success = false,
                            failure = ProviderResponseParseException(
                                "Google subscription response could not be parsed",
                                error,
                            ),
                        )
                    )
                    eventSource.cancel()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val status = response?.code
                if (status == 401) flowScope.launch { tokenProvider.markUnauthorized(accessToken) }
                val failure = if (params.suppressContentLogs) {
                    privateRequestFailure(t, status, response)
                } else if (response != null && !response.isSuccessful) {
                    HttpException("Google Cloud Code Assist request failed", statusCode = status, cause = t)
                } else {
                    t ?: IOException("Google Cloud Code Assist stream failed")
                }
                response?.close()
                resumeOnce(
                    StreamAttempt(
                        success = false,
                        failure = failure,
                    )
                )
            }

            override fun onClosed(eventSource: EventSource) {
                resumeOnce(StreamAttempt(success = true, failure = null))
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        continuation.invokeOnCancellation { source.cancel() }
    }

    private suspend fun postWithEndpointFallback(
        path: String,
        accessToken: String,
        body: String,
        suppressLogs: Boolean,
        customHeaders: Headers = Headers.Builder().build(),
        allowEndpointFallback: Boolean = true,
    ): Response {
        for ((index, endpoint) in MODEL_LIST_ENDPOINTS.withIndex()) {
            val request = Request.Builder()
                .url("$endpoint/$path")
                .headers(customHeaders)
                .withGeminiAntigravityHeaders(accessToken)
                .post(body.toRequestBody(JSON_MEDIA_TYPE).withoutAutomaticReplay())
                .suppressContentLogs(suppressLogs)
                .build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).await() }
            if (
                response.isSuccessful ||
                !allowEndpointFallback ||
                (response.code != 404 && response.code !in 500..599)
            ) {
                if (response.code == 401) tokenProvider.markUnauthorized(accessToken)
                return response
            }
            if (index == MODEL_LIST_ENDPOINTS.lastIndex) return response
            response.close()
        }
        error("Google Cloud Code Assist endpoints are unavailable")
    }

    private fun requestFailure(response: Response): Throwable =
        if (response.code == 401) {
            HttpException("Google subscription login has expired", statusCode = response.code)
        } else {
            HttpException("Google Cloud Code Assist request failed", statusCode = response.code)
        }

    private data class StreamAttempt(
        val success: Boolean,
        val failure: Throwable?,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val MODEL_LIST_ENDPOINTS = GEMINI_GENERATE_ENDPOINTS
    }
}
