package me.rerere.rikkahub.data.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer

internal data class VoiceCallVisualRequest(
    val parts: List<UIMessagePart>,
    val hadActiveSource: Boolean,
    val capturedFrameCount: Int,
    val failedOcrCount: Int,
    val recognizedDescriptions: List<Pair<VoiceCallVisualSource, String>> = emptyList(),
)

internal suspend fun VoiceCallVisualCapture.prepareRequest(
    mainModel: Model?,
    maxFrameCount: Int,
    onFramesCaptured: (List<VoiceCallVisualFrame>) -> Unit = {},
    onRecognitionChanged: (Boolean) -> Unit = {},
): VoiceCallVisualRequest {
    val hadActiveSource = cameraState.value.enabled || screenState.value.active
    val capturedFrames = try {
        if (hadActiveSource) captureFrames() else emptyList()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emptyList()
    }
    val frames = capturedFrames.take(maxFrameCount.coerceAtLeast(0))
    capturedFrames.drop(frames.size).forEach { it.jpeg.fill(0) }
    onFramesCaptured(frames)
    if (frames.isEmpty()) {
        return VoiceCallVisualRequest(emptyList(), hadActiveSource, 0, 0)
    }

    val recognizedFrames = try {
        onRecognitionChanged(true)
        coroutineScope {
            frames.map { frame ->
                async(Dispatchers.IO) {
                    frame.source to OcrTransformer.performOcr(
                        part = frame.toVoiceCallRequestImagePart(),
                        cacheResult = false,
                        suppressContentLogs = true,
                        modelOverride = mainModel,
                    )
                }
            }.awaitAll()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emptyList()
    } finally {
        onRecognitionChanged(false)
        frames.forEach { it.jpeg.fill(0) }
    }
    val successfulDescriptions = recognizedFrames.filterNot { it.second == "[ERROR, OCR failed]" }
    return VoiceCallVisualRequest(
        parts = voiceCallVisualRequestParts(successfulDescriptions),
        hadActiveSource = true,
        capturedFrameCount = frames.size,
        failedOcrCount = frames.size - successfulDescriptions.size,
        recognizedDescriptions = successfulDescriptions,
    )
}
