package me.rerere.rikkahub.data.voice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

internal fun encodeVoiceCallThumbnail(jpeg: ByteArray, maxEdge: Int = 256): ByteArray? {
    if (jpeg.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val sampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / maxEdge).coerceAtLeast(1)
    val bitmap = BitmapFactory.decodeByteArray(
        jpeg,
        0,
        jpeg.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return null
    return try {
        val (width, height) = voiceCallFrameSize(bitmap.width, bitmap.height).let { (w, h) ->
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(w, h))
            (w * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
        }
        val scaled = if (bitmap.width == width && bitmap.height == height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 60, output))
                output.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}
