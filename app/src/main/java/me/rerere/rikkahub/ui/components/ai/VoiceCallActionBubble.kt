package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.LocalLiquidGlass
import me.rerere.rikkahub.ui.theme.liquidGlassChrome

@Composable
internal fun VoiceCallActionBubble(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val glassEnabled = LocalLiquidGlass.current.enabled

    FilledTonalButton(
        enabled = enabled,
        onClick = onClick,
        modifier = if (glassEnabled) modifier.liquidGlassChrome(shape) else modifier,
        shape = shape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (glassEnabled) Color.Transparent else MaterialTheme.colorScheme.secondaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        content = content,
    )
}
