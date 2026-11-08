package me.rerere.rikkahub.data.voice

import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OCR_FAILURE_MARKER
import me.rerere.rikkahub.data.ai.transformers.OcrFailureException
import me.rerere.rikkahub.data.ai.transformers.OcrFailureStage

private const val TAG = "VoiceCallFlow"

internal data class VoiceCallVisualRequest(
    val parts: List<UIMessagePart>,
    val hadActiveSource: Boolean,
    val capturedFrameCount: Int,
    val failedOcrCount: Int,
    val recognizedDescriptions: List<Pair<VoiceCallVisualSource, String>> = emptyList(),
    val ocrFailure: OcrFailureException? = null,
)

internal suspend fun VoiceCallVisualCapture.prepareRequest(
    mainModel: Model?,
    maxFrameCount: Int,
    onFramesCaptured: (List<VoiceCallVisualFrame>) -> Unit = {},
    onRecognitionChanged: (Boolean) -> Unit = {},
    onOcrFailure: (VoiceCallVisualSource, String) -> Unit = { _, _ -> },
    traceId: String? = null,
): VoiceCallVisualRequest {
    val trace = traceId?.take(8) ?: "none"
    val hadActiveSource = cameraState.value.enabled || screenState.value.active
    val startedAt = SystemClock.elapsedRealtime()
    Log.i(
        TAG,
        "visual request prepare start session=$trace cameraEnabled=${cameraState.value.enabled} " +
            "screenActive=${screenState.value.active} maxFrames=$maxFrameCount",
    )
    val capturedFrames = try {
        if (hadActiveSource) captureFrames() else emptyList()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w("VoiceCallFlow", "画面采集异常 exception=${error::class.simpleName ?: "Exception"}")
        emptyList()
    }
    val frames = capturedFrames.take(maxFrameCount.coerceAtLeast(0))
    capturedFrames.drop(frames.size).forEach { it.jpeg.fill(0) }
    Log.i(
        TAG,
        "visual frames captured session=$trace sources=${frames.joinToString(",") { it.source.name }.ifBlank { "none" }} " +
            "jpegBytes=${frames.joinToString(",") { "${it.source.name}:${it.jpeg.size}" }.ifBlank { "none" }} " +
            "discarded=${capturedFrames.size - frames.size}",
    )
    onFramesCaptured(frames)
    if (frames.isEmpty()) {
        Log.i(TAG, "visual request prepare complete session=$trace result=no_frames elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        return VoiceCallVisualRequest(emptyList(), hadActiveSource, 0, 0)
    }

    var batchFailure: OcrFailureException? = null
    val recognizedFrames = try {
        onRecognitionChanged(true)
        coroutineScope {
            frames.map { frame ->
                async(Dispatchers.IO) {
                    val ocrStartedAt = SystemClock.elapsedRealtime()
                    var ocrFailure: OcrFailureException? = null
                    Log.i(TAG, "visual OCR start session=$trace source=${frame.source} jpegBytes=${frame.jpeg.size}")
                    val result = OcrTransformer.performOcr(
                        part = frame.toVoiceCallRequestImagePart(),
                        cacheResult = false,
                        suppressContentLogs = true,
                        modelOverride = mainModel,
                        onFailure = { diagnostic -> onOcrFailure(frame.source, diagnostic) },
                        onError = { ocrFailure = it },
                    )
                    Log.i(
                        TAG,
                        "visual OCR complete session=$trace source=${frame.source} " +
                            "success=${result != "[ERROR, OCR failed]"} chars=${result.length} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - ocrStartedAt}",
                    )
                    Triple(frame.source, result, ocrFailure)
                }
            }.awaitAll()
        }
    } catch (error: CancellationException) {
        Log.i(TAG, "visual request prepare cancelled session=$trace")
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "visual request OCR batch failed session=$trace exception=${error::class.simpleName ?: "Exception"}")
        batchFailure = OcrFailureException(OcrFailureStage.RESPONSE, cause = error)
        emptyList()
    } finally {
        onRecognitionChanged(false)
        frames.forEach { it.jpeg.fill(0) }
    }
    val successfulDescriptions = recognizedFrames.filterNot { it.second == OCR_FAILURE_MARKER }
        .map { it.first to it.second }
    val failureCount = frames.size - successfulDescriptions.size
    val firstFailure = recognizedFrames.firstNotNullOfOrNull { it.third } ?: batchFailure
    val ocrFailure = firstFailure?.let {
        OcrFailureException(
            stage = it.stage,
            statusCode = it.statusCode,
            failedImages = failureCount,
            cause = it.cause,
        )
    }
    val request = VoiceCallVisualRequest(
        parts = voiceCallVisualRequestParts(successfulDescriptions),
        hadActiveSource = true,
        capturedFrameCount = frames.size,
        failedOcrCount = failureCount,
        recognizedDescriptions = successfulDescriptions,
        ocrFailure = ocrFailure,
    )
    Log.i(
        TAG,
        "visual request prepare complete session=$trace frames=${request.capturedFrameCount} " +
            "recognized=${successfulDescriptions.joinToString(",") { "${it.first}:${it.second.length}" }.ifBlank { "none" }} " +
            "failed=${request.failedOcrCount} parts=${request.parts.joinToString(",") { it::class.simpleName ?: "unknown" }.ifBlank { "none" }} " +
            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
    )
    return request
}
