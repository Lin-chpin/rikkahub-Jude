package me.rerere.rikkahub.data.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer

internal data class VoiceCallVisualRequest(
    val parts: List<UIMessagePart>,
    val hadActiveSource: Boolean,
    val capturedFrameCount: Int,
    val failedOcrCount: Int,
)

internal suspend fun VoiceCallVisualCapture.prepareRequest(
    useOcrModel: Boolean,
    mainModelSupportsImages: Boolean,
): VoiceCallVisualRequest {
    val hadActiveSource = cameraState.value.enabled || screenState.value.active
    val frames = try {
        if (hadActiveSource) captureFrames() else emptyList()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emptyList()
    }
    if (frames.isEmpty()) {
        return VoiceCallVisualRequest(emptyList(), hadActiveSource, 0, 0)
    }

    if (!useOcrModel && mainModelSupportsImages) {
        return VoiceCallVisualRequest(
            parts = voiceCallVisualRequestParts(
                frames = frames,
                useOcrModel = false,
                mainModelSupportsImages = true,
                recognizedDescriptions = emptyList(),
            ),
            hadActiveSource = true,
            capturedFrameCount = frames.size,
            failedOcrCount = 0,
        )
    }
    if (!useOcrModel) {
        return VoiceCallVisualRequest(emptyList(), true, frames.size, frames.size)
    }

    val recognizedFrames = try {
        withContext(Dispatchers.IO) {
            frames.map { frame ->
                frame.source to OcrTransformer.performOcr(
                    part = frame.toVoiceCallRequestImagePart(),
                    cacheResult = false,
                    suppressContentLogs = true,
                )
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emptyList()
    }
    val successfulDescriptions = recognizedFrames.filterNot { it.second == "[ERROR, OCR failed]" }
    return VoiceCallVisualRequest(
        parts = voiceCallVisualRequestParts(
            frames = frames,
            useOcrModel = true,
            mainModelSupportsImages = mainModelSupportsImages,
            recognizedDescriptions = successfulDescriptions,
        ),
        hadActiveSource = true,
        capturedFrameCount = frames.size,
        failedOcrCount = frames.size - successfulDescriptions.size,
    )
}
