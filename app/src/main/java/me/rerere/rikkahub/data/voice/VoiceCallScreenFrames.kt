package me.rerere.rikkahub.data.voice

import android.graphics.Bitmap
import android.media.Image
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

/** ImageReader 持续排空，只保留一张最新图像，发言时才转换和编码。 */
internal object VoiceCallScreenFrames {
    private val lock = Any()
    private var owner: String? = null
    private var image: Image? = null
    private var readerGeneration = 0L
    private var visible = true
    private val frameVersion = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(VoiceCallScreenState())
    val state = mutableState.asStateFlow()

    fun prepare(sessionId: String) = synchronized(lock) {
        image?.close()
        image = null
        owner = sessionId
        visible = true
        mutableState.value = VoiceCallScreenState()
    }

    fun owns(sessionId: String): Boolean = synchronized(lock) { owner == sessionId }

    fun started(sessionId: String) = synchronized(lock) {
        if (owner == sessionId) mutableState.value = VoiceCallScreenState(active = true)
    }

    fun newReader(sessionId: String): Long = synchronized(lock) {
        if (owner == sessionId) {
            image?.close()
            image = null
        }
        ++readerGeneration
    }

    fun offer(sessionId: String, generation: Long, next: Image) = synchronized(lock) {
        if (owner != sessionId || generation != readerGeneration || !visible) {
            next.close()
            return@synchronized
        }
        image?.close()
        image = next
        frameVersion.value++
    }

    fun setVisible(sessionId: String, value: Boolean) = synchronized(lock) {
        if (owner == sessionId) {
            visible = value
            if (!value) {
                image?.close()
                image = null
            }
        }
    }

    fun clearFrame(sessionId: String): Long = synchronized(lock) {
        if (owner == sessionId) {
            image?.close()
            image = null
        }
        frameVersion.value
    }

    suspend fun snapshotAfterFrame(sessionId: String, afterVersion: Long): ByteArray? {
        if (withTimeoutOrNull(1_500) { frameVersion.first { it > afterVersion } } == null) return null
        return snapshot(sessionId)
    }

    fun stop(sessionId: String, error: String? = null) = synchronized(lock) {
        if (owner == sessionId) {
            image?.close()
            image = null
            owner = null
            mutableState.value = VoiceCallScreenState(error = error)
            true
        } else false
    }

    fun snapshot(sessionId: String): ByteArray? = synchronized(lock) {
        if (owner != sessionId || !state.value.active) return@synchronized null
        val current = image ?: return@synchronized null
        val plane = current.planes.firstOrNull() ?: return@synchronized null
        check(plane.pixelStride == 4)
        val bitmap = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
        try {
            // 最后一行可能不含 padding，逐行取有效像素避免 BufferUnderflow。
            val pixels = ByteBuffer.allocate(current.width * current.height * 4)
            val source = plane.buffer.duplicate()
            repeat(current.height) { row ->
                source.limit(source.capacity())
                source.position(row * plane.rowStride)
                source.limit(source.position() + current.width * 4)
                pixels.put(source)
            }
            pixels.flip()
            bitmap.copyPixelsFromBuffer(pixels)
            encodeVoiceCallFrame(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
