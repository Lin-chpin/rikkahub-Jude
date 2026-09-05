package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import me.rerere.tts.model.PlaybackStatus

private val voiceWaveformHeights = floatArrayOf(
    0.36f, 0.58f, 0.82f, 0.5f, 0.7f, 0.94f, 0.62f, 0.42f,
    0.78f, 0.58f, 0.34f, 0.52f, 0.76f, 0.96f, 0.68f, 0.44f,
    0.72f, 0.54f, 0.82f, 0.62f, 0.38f, 0.56f, 0.78f, 0.48f,
)

@Composable
internal fun ChatVoiceWaveform(
    playbackStatus: PlaybackStatus,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val playedColor = when (playbackStatus) {
        PlaybackStatus.Playing -> colorScheme.primary
        PlaybackStatus.Paused -> colorScheme.primary.copy(alpha = 0.78f)
        PlaybackStatus.Buffering -> colorScheme.primary.copy(alpha = 0.62f)
        PlaybackStatus.Ended -> colorScheme.primary.copy(alpha = 0.78f)
        PlaybackStatus.Error -> colorScheme.error.copy(alpha = 0.68f)
        PlaybackStatus.Idle -> colorScheme.primary.copy(alpha = 0.68f)
    }
    val unplayedColor = colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    val clampedProgress = progress.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp),
    ) {
        val step = size.width / voiceWaveformHeights.size
        val barWidth = (step * 0.56f).coerceAtLeast(2.dp.toPx())
        val centerY = size.height / 2f

        voiceWaveformHeights.forEachIndexed { index, baseHeight ->
            val barHeight = size.height * baseHeight
            val barProgress = (index + 0.5f) / voiceWaveformHeights.size
            drawRoundRect(
                color = if (barProgress <= clampedProgress) playedColor else unplayedColor,
                topLeft = Offset(
                    x = step * index + (step - barWidth) / 2f,
                    y = centerY - barHeight / 2f,
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
