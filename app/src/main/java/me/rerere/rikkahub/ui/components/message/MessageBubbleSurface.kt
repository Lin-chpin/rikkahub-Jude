package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.liquidGlassChrome

internal val ChatMessageBubbleShape: Shape = RoundedCornerShape(16.dp)

@Composable
internal fun MessageBubbleSurface(
    modifier: Modifier = Modifier,
    shape: Shape = ChatMessageBubbleShape,
    containerColor: Color,
    contentColor: Color = contentColorFor(containerColor),
    applyLiquidGlass: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (applyLiquidGlass) {
        val glassModifier = modifier
            .liquidGlassChrome(shape)
            .clip(shape)
        Box(
            modifier = if (onClick == null) glassModifier else glassModifier.clickable(onClick = onClick),
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
        }
    } else if (onClick == null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            content = content,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            content = content,
        )
    }
}
