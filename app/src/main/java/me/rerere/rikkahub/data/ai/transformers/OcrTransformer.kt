package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderResponseParseException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.OcrOpenAIConfig
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.voice.supportsVoiceCallImages
import me.rerere.common.http.privateFailureDiagnosticDetail
import me.rerere.common.http.privateFailureStatusCode
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days

private const val TAG = "OcrTransformer"
internal const val OCR_FAILURE_MARKER = "[ERROR, OCR failed]"

internal enum class OcrFailureStage { CONFIGURATION, REQUEST, RESPONSE, NO_TEXT }

internal class OcrFailureException(
    val stage: OcrFailureStage,
    val statusCode: Int? = null,
    val failedImages: Int = 1,
    cause: Throwable? = null,
) : Exception("Image OCR failed during ${stage.name.lowercase()}", cause) {
    fun userMessage(): String {
        val causes = causeChain().toList()
        causes.filterIsInstance<HttpException>()
            .firstNotNullOfOrNull { it.ocrProviderErrorMessage() }
            ?.let { return "$it；本轮已继续发送文字内容。" }
        return when {
            statusCode == 400 -> "图片识别接口拒绝了请求（HTTP 400）；请检查识图配置。本轮已继续发送文字内容。"
            statusCode == 401 -> "图片识别接口身份验证失败；本轮已继续发送文字内容。"
            statusCode == 403 -> "图片识别接口拒绝访问；请检查识图权限。本轮已继续发送文字内容。"
            statusCode == 404 -> "图片识别接口地址或请求资源未找到（HTTP 404）；请检查识图配置。本轮已继续发送文字内容。"
            statusCode == 408 -> "图片识别服务端报告请求超时（HTTP 408）；本轮已继续发送文字内容。"
            statusCode == 413 -> "图片识别请求过大（HTTP 413）；请缩小图片后重试。本轮已继续发送文字内容。"
            statusCode == 429 -> "图片识别请求受到限流或额度限制；本轮已继续发送文字内容。"
            statusCode in 500..599 -> "图片识别服务端返回 HTTP $statusCode 错误；本轮已继续发送文字内容。"
            statusCode != null -> "图片识别接口返回 HTTP $statusCode，具体原因无法从状态码确认；本轮已继续发送文字内容。"
            causes.any { it is java.net.SocketTimeoutException } ->
                "图片识别流程超时；无法确认具体网络阶段。本轮已继续发送文字内容。"
            causes.any { it is java.net.UnknownHostException } ->
                "无法解析图片识别接口域名；本轮已继续发送文字内容。"
            causes.any { it is javax.net.ssl.SSLException } ->
                "图片识别接口安全连接失败；本轮已继续发送文字内容。"
            causes.any { it is ProviderResponseParseException } ->
                "图片识别响应解析失败；上游返回的数据不符合可识别格式。本轮已继续发送文字内容。"
            else -> when (stage) {
                OcrFailureStage.CONFIGURATION -> "图片识别模型或供应商配置不完整；本轮已继续发送文字内容。"
                OcrFailureStage.REQUEST -> "图片识别请求失败，具体原因未能确认；本轮已继续发送文字内容。"
                OcrFailureStage.RESPONSE -> "图片识别响应解析失败；本轮已继续发送文字内容。"
                OcrFailureStage.NO_TEXT -> "图片识别服务未返回可用文字；本轮已继续发送文字内容。"
            }
        }
    }

    private fun HttpException.ocrProviderErrorMessage(): String? {
        val code = providerCode.orEmpty().lowercase()
        val type = providerType.orEmpty().lowercase()
        return when {
            code in setOf("invalid_api_key", "authentication_error", "unauthorized") ||
                type == "authentication_error" -> "图片识别接口身份验证失败"
            code in setOf("rate_limit_exceeded", "rate_limited", "too_many_requests") ||
                type == "rate_limit_error" -> "图片识别请求受到限流"
            code in setOf("insufficient_quota", "quota_exceeded", "payment_issue", "billing_hard_limit_reached") ->
                "图片识别接口报告账户额度或付款状态不可用"
            code in setOf("invalid_request_error", "invalid_parameter", "unsupported_parameter") ||
                type == "invalid_request_error" -> "图片识别接口拒绝了请求参数"
            code in setOf("model_not_found", "unknown_model") -> "图片识别接口未找到所选模型"
            code in setOf("permission_denied", "access_denied") || type == "permission_error" ->
                "图片识别接口拒绝访问"
            else -> null
        }
    }

    private fun causeChain(): Sequence<Throwable> = sequence {
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = this@OcrFailureException.cause
        while (current != null && visited.add(current)) {
            yield(current)
            current = current.cause
        }
    }
}

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.supportsVoiceCallImages()) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.isOcrInput() }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                val failures = mutableListOf<OcrFailureException>()
                val transformed = messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.isOcrInput() -> {
                                    val result = performOcr(part, onError = { failures.add(it) })
                                    UIMessagePart.Text(
                                        if (result == OCR_FAILURE_MARKER) "图片识别未完成；模型未收到该图片内容。"
                                        else result,
                                    )
                                }

                                else -> part
                            }
                        }
                    )
                }
                failures.firstOrNull()?.let { failure ->
                    ctx.onWarning(failure.copyFailureCount(failures.size))
                }
                transformed
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    internal suspend fun performOcr(
        part: UIMessagePart.Image,
        cacheResult: Boolean = true,
        suppressContentLogs: Boolean = false,
        modelOverride: Model? = null,
        onFailure: ((String) -> Unit)? = null,
        onError: ((OcrFailureException) -> Unit)? = null,
    ): String {
        var stage = OcrFailureStage.CONFIGURATION
        return try {
            val settings = get<SettingsStore>().settingsFlow.value
            val ocrConfig = settings.ocrOpenAIConfig
            val hasConfiguredOcrApi = ocrConfig.enabled &&
                ocrConfig.modelId.isNotBlank() && ocrConfig.baseUrl.isNotBlank()
            val model = if (hasConfiguredOcrApi) {
                ocrConfig.modelId.trim().takeIf { it.isNotBlank() }?.let { modelId ->
                    Model(
                        modelId = modelId,
                        displayName = modelId,
                        type = ModelType.CHAT,
                    )
                } ?: settings.ocrModelId?.let(settings::findModelById)
                ?: settings.getCurrentChatModel()
            } else {
                settings.ocrModelId?.let(settings::findModelById)
                    ?: modelOverride
                    ?: settings.getCurrentChatModel()
            } ?: throw IllegalStateException(
                "No chat model available for OCR. Configure the chat model in Settings > Model Settings."
            )
            val providerSetting = if (hasConfiguredOcrApi) {
                ocrConfig.toProviderSetting()
            } else {
                model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found for OCR")
            }
            val provider = get<ProviderManager>().getProviderByType(providerSetting)
            val useCache = cacheResult && part.url.startsWith("file:")
            val route = if (hasConfiguredOcrApi) "dedicated_ocr" else "assistant_model"
            val startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "performOcr: started route=$route provider=${provider.javaClass.simpleName} modelType=${model.type}")
            if (useCache) {
                runCatching { cache.get(part.url) }.onFailure {
                    Log.w(TAG, "performOcr: cache read failed exception=${it::class.simpleName}")
                }.getOrNull()?.let { cachedResult ->
                    Log.i(TAG, "performOcr: Using cached image OCR result")
                    return cachedResult
                }
            }

            stage = OcrFailureStage.REQUEST
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(settings.ocrPrompt),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image(part.url))
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    suppressContentLogs = suppressContentLogs,
                ),
            )
            stage = OcrFailureStage.RESPONSE
            val responseMessage = result.choices.firstOrNull()?.message
                ?: throw OcrFailureException(OcrFailureStage.RESPONSE)
            val content = responseMessage.toText()
            if (content.isBlank()) {
                Log.w(TAG, "performOcr: OCR returned no text route=$route elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                runCatching { onFailure?.invoke("response=no_text") }
                val failure = OcrFailureException(OcrFailureStage.NO_TEXT)
                runCatching { onError?.invoke(failure) }
                return OCR_FAILURE_MARKER
            }
            Log.i(
                TAG,
                "performOcr: OCR completed route=$route chars=${content.length} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            val ocrResult = """
                <image_file_ocr>
                   $content
                </image_file_ocr>
                * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
            """.trimIndent()

            if (useCache) runCatching { cache.put(part.url, ocrResult) }.onFailure {
                Log.w(TAG, "performOcr: cache write failed exception=${it::class.simpleName}")
            }
            return ocrResult
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val causes = error.causeChain().toList()
            val errorTypes = causes.take(4).joinToString(">") { it::class.simpleName ?: "Throwable" }
            val statusCode = causes.filterIsInstance<HttpException>().firstNotNullOfOrNull { it.statusCode }
                ?: privateFailureStatusCode(error)
            val failure = (error as? OcrFailureException) ?: OcrFailureException(
                stage = if (causes.any { it is ProviderResponseParseException }) OcrFailureStage.RESPONSE else stage,
                statusCode = statusCode,
                cause = error,
            )
            val diagnosticDetails = privateFailureDiagnosticDetail(error)
                ?: "http_status=${statusCode ?: "none"}"
            val diagnostic = "exception_chain=$errorTypes $diagnosticDetails"
            Log.w(TAG, "performOcr: OCR failed $diagnostic")
            runCatching { onFailure?.invoke(diagnostic) }
            runCatching { onError?.invoke(failure) }
            OCR_FAILURE_MARKER
        }
    }

    private fun OcrFailureException.copyFailureCount(count: Int) = OcrFailureException(
        stage = stage,
        statusCode = statusCode,
        failedImages = count,
        cause = cause,
    )

    private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = this@causeChain
        while (current != null && visited.add(current)) {
            yield(current)
            current = current.cause
        }
    }

    private fun UIMessagePart.Image.isOcrInput() =
        url.startsWith("file:") || url.startsWith("data:image/")

    private fun OcrOpenAIConfig.toProviderSetting(): ProviderSetting {
        return ProviderSetting.OpenAI(
            apiKey = apiKey,
            baseUrl = baseUrl.trimEnd('/'),
            chatCompletionsPath = chatCompletionsPath,
            useResponseApi = useResponseApi,
        )
    }


}
