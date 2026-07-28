package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant

@Composable
fun AssistantBackground(
    setting: Settings,
    modifier: Modifier,
    assistant: Assistant? = null,
    useVoiceCallBackground: Boolean = false,
) {
    val targetAssistant = assistant ?: setting.getCurrentAssistant()
    val background = if (useVoiceCallBackground) {
        targetAssistant.voiceCallBackground
    } else {
        targetAssistant.background
    }
    val backgroundOpacity = if (useVoiceCallBackground) {
        targetAssistant.voiceCallBackgroundOpacity
    } else {
        targetAssistant.backgroundOpacity
    }
    if (background != null) {
        val backgroundColor = MaterialTheme.colorScheme.background
        val previewOpacity = backgroundOpacity.coerceIn(0f, 1f)
        Box(modifier = modifier) {
            AsyncImage(
                model = background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(previewOpacity)
            )

            // 全屏渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                backgroundColor.copy(alpha = 0.2f),
                                backgroundColor.copy(alpha = 0.5f)
                            )
                        )
                    )
            )
        }
    }
}
