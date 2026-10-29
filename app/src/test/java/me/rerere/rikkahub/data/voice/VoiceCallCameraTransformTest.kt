package me.rerere.rikkahub.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallCameraTransformTest {
    @Test
    fun `front and back previews handle every display rotation`() {
        for (frontFacing in listOf(false, true)) {
            val sensorOrientation = if (frontFacing) 270 else 90
            for (displayRotation in listOf(0, 90, 180, 270)) {
                val transform = voiceCallCameraPreviewTransform(
                    viewWidth = 322,
                    viewHeight = 448,
                    previewWidth = 1024,
                    previewHeight = 768,
                    sensorOrientationDegrees = sensorOrientation,
                    displayRotationDegrees = displayRotation,
                    frontFacing = frontFacing,
                )

                assertEquals(-displayRotation.toFloat(), transform.rotationDegrees, 0.001f)
                assertEquals(frontFacing, transform.mirrorHorizontally)
                assertTrue(transform.scaleX.isFinite() && transform.scaleX > 0f)
                assertTrue(transform.scaleY.isFinite() && transform.scaleY > 0f)
            }
        }
    }

    @Test
    fun `portrait preview does not reapply sensor rotation and only front camera mirrors`() {
        val front = voiceCallCameraPreviewTransform(
            viewWidth = 322,
            viewHeight = 448,
            previewWidth = 1024,
            previewHeight = 768,
            sensorOrientationDegrees = 270,
            displayRotationDegrees = 0,
            frontFacing = true,
        )
        val back = voiceCallCameraPreviewTransform(
            viewWidth = 322,
            viewHeight = 448,
            previewWidth = 1024,
            previewHeight = 768,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
            frontFacing = false,
        )

        assertEquals(0f, front.rotationDegrees, 0.001f)
        assertEquals(0f, back.rotationDegrees, 0.001f)
        assertTrue(front.mirrorHorizontally)
        assertFalse(back.mirrorHorizontally)
        assertEquals(1.0436f, front.scaleX, 0.001f)
        assertEquals(1f, front.scaleY, 0.001f)
        assertEquals(front.scaleX, back.scaleX, 0.001f)
        assertEquals(front.scaleY, back.scaleY, 0.001f)
    }

    @Test
    fun `display quarter turn uses view rotation and swaps crop axes`() {
        val transform = voiceCallCameraPreviewTransform(
            viewWidth = 448,
            viewHeight = 322,
            previewWidth = 1024,
            previewHeight = 768,
            sensorOrientationDegrees = 270,
            displayRotationDegrees = 90,
            frontFacing = true,
        )

        assertEquals(-90f, transform.rotationDegrees, 0.001f)
        assertEquals(0.75f, transform.scaleX, 0.001f)
        assertEquals(1.3913f, transform.scaleY, 0.001f)
        assertTrue(transform.mirrorHorizontally)
    }

    @Test
    fun `reverse display turn is represented and wide sensor stays undistorted`() {
        val transform = voiceCallCameraPreviewTransform(
            viewWidth = 322,
            viewHeight = 448,
            previewWidth = 1280,
            previewHeight = 576,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 180,
            frontFacing = false,
        )

        assertEquals(-180f, transform.rotationDegrees, 0.001f)
        assertFalse(transform.mirrorHorizontally)
        assertEquals(1.0f, transform.scaleX, 0.001f)
        assertEquals(1.5972f, transform.scaleY, 0.001f)
    }
}
