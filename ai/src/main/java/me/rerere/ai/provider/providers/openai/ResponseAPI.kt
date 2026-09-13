package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.SSEEventSource
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import kotlin.time.Clock

private const val TAG = "ResponseAPI"
private const val EVENT_STREAM_MEDIA_TYPE = "text/event-stream"

internal fun okhttp3.Request.Builder.acceptEventStream(): okhttp3.Request.Builder =
    header("Accept", EVENT_STREAM_MEDIA_TYPE)

class ResponseAPI(
    private val client: OkHttpClient,
    keyRoulette: KeyRoulette = KeyRoulette.default(),
    codexTokenProvider: OpenAICodexTokenProvider? = null,
) : OpenAIImpl {
    private val authenticator = OpenAIRequestAuthenticator(keyRoulette, codexTokenProvider)

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        if (providerSetting.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) {
            return collectStreamingTextGeneration(
                model = params.model,
                stream = streamText(providerSetting, messages, params),
            )
        }

        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val requestBuilder = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
        val request = authenticator.authenticate(requestBuilder, providerSetting).build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val output = parseResponseOutput(bodyJson)

        return output
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val requestBuilder = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .acceptEventStream()
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
        val request = authenticator.authenticate(requestBuilder, providerSetting).build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                Log.d(TAG, "onEvent: $id/$type (${data.length} chars)")
                val json = json.parseToJsonElement(data).jsonObject
                val chunk = parseResponseDelta(json)
                if (chunk != null) {
                    trySend(chunk)
                }
                if (type == "response.completed") {
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = SSEEventSource.factory(
            callFactory = client,
            allowMissingContentType = providerSetting.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION,
        )
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = resolveResponseProviderCapabilities(host)
        val body = buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            if (!params.model.tools.contains(BuiltInTools.ImageGeneration)) {
                put("store", false)
            }

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // Keep the stable prefix in instructions. Conversation summaries and
            // runtime context are appended by GenerationHandler and change often.
            val systemPrompt = messages.firstOrNull { it.role == MessageRole.SYSTEM }
                ?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("\n") { it.text }
                .orEmpty()
            val (stableInstructions, dynamicInstructions) = splitResponseSystemPrompt(systemPrompt)
            if (stableInstructions.isNotBlank()) {
                put("instructions", stableInstructions)
            }

            // messages
            put(
                "input",
                buildInput(
                    messages,
                    dynamicInstructions,
                    includeReasoning = providerSetting.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION,
                )
            )

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    if (level != ReasoningLevel.AUTO) {
                        put("effort", level.effort)
                    }
                })
                if (capabilities.supportEncryptedContent) {
                    put("include", buildJsonArray {
                        add("reasoning.encrypted_content")
                    })
                }
            }

            // tools
            if (params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put(
                                "parameters",
                                json.encodeToJsonElement(
                                    tool.parameters()
                                )
                            )
                        })
                    }
                }
            }
            // built-in tools
            if (params.model.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)

        return if (providerSetting.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) {
            JsonObject(body + ("stream" to JsonPrimitive(true)))
        } else {
            body
        }
    }

    internal fun buildMessages(
        messages: List<UIMessage>,
        includeReasoning: Boolean = false,
    ) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantItems(message, includeReasoning)
                } else {
                    addUserItems(message)
                }
            }
    }

    internal fun buildInput(
        messages: List<UIMessage>,
        dynamicInstructions: String? = null,
        includeReasoning: Boolean = false,
    ) = buildJsonArray {
        val messageItems = buildMessages(messages, includeReasoning)
        val dynamic = dynamicInstructions?.trim().orEmpty()

        messageItems.forEach { add(it) }
        if (dynamic.isNotBlank()) addDeveloperInstruction(dynamic)
    }

    private fun JsonArrayBuilder.addDeveloperInstruction(text: String) {
        add(buildJsonObject {
            put("role", "developer")
            put("content", text)
        })
    }

    private fun JsonArrayBuilder.addAssistantItems(
        message: UIMessage,
        includeReasoning: Boolean,
    ) {
        val groups = groupPartsByToolBoundary(
            if (includeReasoning) message.parts else message.parts.filterNot { it is UIMessagePart.Reasoning }
        )
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    part.metadata?.get("reasoning_id")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                                        put("id", it)
                                    }
                                    put("summary", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "summary_text")
                                            put("text", part.reasoning)
                                        })
                                    })
                                    part.metadata?.get("encrypted_content")?.jsonPrimitiveOrNull?.contentOrNull?.let {
                                        put(
                                            "encrypted_content",
                                            part.metadata?.get("encrypted_content")?.jsonPrimitive?.contentOrNull ?: ""
                                        )
                                    }
                                })
                            }

                            is UIMessagePart.Image -> {
                                val callId = part.metadata?.get("openai_image_call_id")?.jsonPrimitive?.contentOrNull
                                if (callId != null) {
                                    if (contentBuffer.isNotEmpty()) {
                                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                        contentBuffer.clear()
                                    }
                                    add(buildJsonObject {
                                        put("type", "image_generation_call")
                                        put("id", callId)
                                    })
                                } else {
                                    contentBuffer.add(part)
                                }
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // 输出 function_call + function_call_output
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            put("arguments", tool.input)
                        })
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            put(
                                "output",
                                tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", if (role == MessageRole.USER) "input_image" else "output_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun parseResponseDelta(jsonObject: JsonObject): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
        parseResponseReasoningEvent(jsonObject)?.let { return it }

        when (chunkType) {
            "response.output_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(
                                jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_item.added" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                if (type == "function_call") {
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Tool(
                                            toolCallId = id,
                                            toolName = item["name"]?.jsonPrimitive?.content ?: "",
                                            input = item["arguments"]?.jsonPrimitive?.content
                                                ?: "",
                                            output = emptyList()
                                        )
                                    )
                                ),
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = buildJsonObject {
                                                put("encrypted_content", encryptedContent)
                                                put("reasoning_id", id)
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val callId = item["id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    return MessageChunk(
                        id = callId,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(
                                            url = "",
                                            metadata = buildJsonObject {
                                                put("openai_image_call_id", callId)
                                            }
                                        )
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.output_item.done" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = buildJsonObject {
                                                put("encrypted_content", encryptedContent)
                                                put("reasoning_id", id)
                                            }
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val result = item["result"]?.jsonPrimitive?.content ?: error("result not found")
                    return MessageChunk(
                        id = item["id"]?.jsonPrimitive?.content ?: error("item_id not found"),
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(
                                            url = result,
                                            metadata = buildJsonObject {
                                                put("openai_image_call_id", item["id"]?.jsonPrimitive?.content ?: "")
                                            }
                                        )
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.function_call_arguments.done" -> {
                val toolCallId =
                    jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
                val arguments =
                    jsonObject["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                return MessageChunk(
                    id = toolCallId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = toolCallId,
                                        toolName = "",
                                        input = arguments,
                                        output = emptyList()
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    ),
                )
            }

            "response.completed" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = emptyList(),
                    usage = parseTokenUsage(jsonObject["response"]?.jsonObject?.get("usage")?.jsonObject)
                )
            }
        }

        return null
    }

    private fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val summary = output["summary"]?.jsonArray ?: error("summary not found")
                    summary.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now()
                                    )
                                )
                            }
                        }
                    }
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Text(
                                        text = text
                                    )
                                )
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }
            }
        }

        return MessageChunk(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = parts,
                    ),
                    finishReason = null,
                    delta = null
                )
            ),
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObject)
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }
}

internal suspend fun collectStreamingTextGeneration(
    model: Model,
    stream: Flow<MessageChunk>,
): MessageChunk {
    var messages = listOf(UIMessage(role = MessageRole.USER, parts = emptyList()))
    var responseId = ""
    var responseModel = model.modelId
    var finishReason: String? = null
    var usage: TokenUsage? = null

    stream.collect { chunk ->
        messages = messages.handleMessageChunk(chunk, model)
        if (chunk.id.isNotBlank()) responseId = chunk.id
        if (chunk.model.isNotBlank()) responseModel = chunk.model
        chunk.choices.firstOrNull()?.finishReason?.let { finishReason = it }
        chunk.usage?.let { usage = it }
    }

    val assistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?: error("Streaming response completed without an assistant message")
    return MessageChunk(
        id = responseId,
        model = responseModel,
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = null,
                message = assistant,
                finishReason = finishReason,
            )
        ),
        usage = usage,
    )
}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true,
)

private const val COMPRESSED_SUMMARY_MARKER =
    "The following is a compressed summary of earlier messages in this conversation."

private fun splitResponseSystemPrompt(prompt: String): Pair<String, String?> {
    val markerIndex = prompt.indexOf(COMPRESSED_SUMMARY_MARKER)
    if (markerIndex < 0) return prompt.trim() to null

    return prompt.substring(0, markerIndex).trim() to prompt.substring(markerIndex).trim()
}

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false,
        )

        else -> ResponseProviderCapabilities()
    }
}

