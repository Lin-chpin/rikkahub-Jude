package me.rerere.rikkahub.data.voice

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal fun voiceCallFrameSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(1.0, 960.0 / maxOf(width, height))
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

internal fun encodeVoiceCallFrame(bitmap: Bitmap): ByteArray {
    val (width, height) = voiceCallFrameSize(bitmap.width, bitmap.height)
    val scaled = if (bitmap.width == width && bitmap.height == height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
    return try {
        ByteArrayOutputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 70, output))
            output.toByteArray()
        }
    } finally {
        if (scaled !== bitmap) scaled.recycle()
    }
}
