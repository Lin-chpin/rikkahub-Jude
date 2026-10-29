package me.rerere.rikkahub.data.voice

import kotlin.math.max

internal data class VoiceCallCameraPreviewTransform(
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Float,
    val mirrorHorizontally: Boolean,
)

internal fun voiceCallCameraPreviewTransform(
    viewWidth: Int,
    viewHeight: Int,
    previewWidth: Int,
    previewHeight: Int,
    sensorOrientationDegrees: Int,
    displayRotationDegrees: Int,
    frontFacing: Boolean,
): VoiceCallCameraPreviewTransform {
    require(viewWidth > 0 && viewHeight > 0 && previewWidth > 0 && previewHeight > 0)

    val sign = if (frontFacing) 1 else -1
    val relativeRotation = ((sensorOrientationDegrees - displayRotationDegrees * sign) % 360 + 360) % 360
    val swapsAxes = relativeRotation % 180 != 0
    val width = viewWidth.toFloat()
    val height = viewHeight.toFloat()
    val bufferWidth = previewWidth.toFloat()
    val bufferHeight = previewHeight.toFloat()

    val scaleX: Float
    val scaleY: Float
    if (sensorOrientationDegrees == 0) {
        scaleX = width / if (swapsAxes) bufferWidth else bufferHeight
        scaleY = height / if (swapsAxes) bufferHeight else bufferWidth
    } else {
        scaleX = width / if (swapsAxes) bufferHeight else bufferWidth
        scaleY = height / if (swapsAxes) bufferWidth else bufferHeight
    }

    val centerCropScale = max(scaleX, scaleY)
    val matrixScaleX: Float
    val matrixScaleY: Float
    if (swapsAxes) {
        matrixScaleX = centerCropScale / scaleX
        matrixScaleY = centerCropScale / scaleY
    } else {
        matrixScaleX = height / width / scaleY * centerCropScale
        matrixScaleY = width / height / scaleX * centerCropScale
    }

    return VoiceCallCameraPreviewTransform(
        scaleX = matrixScaleX,
        scaleY = matrixScaleY,
        rotationDegrees = -displayRotationDegrees.toFloat(),
        mirrorHorizontally = frontFacing,
    )
}
