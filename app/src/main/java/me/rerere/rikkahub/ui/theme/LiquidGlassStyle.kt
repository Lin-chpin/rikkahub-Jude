@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.hazeEffect

data class LiquidGlassStyle(
    val enabled: Boolean = false,
    val blurEnabled: Boolean = false,
    val opacity: Float = 0.20f,
    val blurRadius: Float = 24f,
) {
    // Keep stored settings stable; the blur slider now spans 0–36 dp for a visible frost range.
    val surfaceOpacity: Float get() = opacity.coerceIn(0f, 1f) * 0.50f
    val backgroundBlurRadius: Float get() = blurRadius.coerceIn(0f, 60f) * 0.6f
}
val LocalLiquidGlass = staticCompositionLocalOf { LiquidGlassStyle() }
val LocalLiquidGlassWorkspace = staticCompositionLocalOf { LiquidGlassStyle() }
val LocalLiquidGlassHaze = staticCompositionLocalOf<HazeState?> { null }

@Composable
internal fun LiquidGlassScene(
    enabled: Boolean,
    blurEnabled: Boolean,
    opacity: Float,
    blurRadius: Float,
    workspaceOpacity: Float = opacity,
    workspaceBlurRadius: Float = blurRadius,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLiquidGlass provides LiquidGlassStyle(enabled, enabled && blurEnabled, opacity.coerceIn(0f, 1f), blurRadius.coerceIn(0f, 60f)),
        LocalLiquidGlassWorkspace provides LiquidGlassStyle(enabled, enabled && blurEnabled, workspaceOpacity.coerceIn(0f, 1f), workspaceBlurRadius.coerceIn(0f, 60f)),
        content = content,
    )
}

/** Decoration only: no gestures, focus, padding or touch-target changes. */
@Composable
fun Modifier.liquidGlassChrome(
    shape: Shape = RectangleShape,
    hazeState: HazeState? = LocalLiquidGlassHaze.current,
): Modifier {
    val glass = LocalLiquidGlass.current
    if (!glass.enabled) return this
    val tint = Color.White.copy(alpha = glass.surfaceOpacity)
    // Zero blur still needs the backdrop layer; removing it changes clear glass into a different material.
    val blur = if (glass.blurEnabled && hazeState != null) {
        Modifier.hazeEffect(hazeState) {
            clipToAreasBounds = false
            blurEffect {
                blurEnabled = true
                style = glass.backdropBlurStyle()
                blurredEdgeTreatment = BlurredEdgeTreatment(shape)
            }
        }
    } else {
        Modifier
    }
    return shadow(2.dp, shape)
        .clip(shape)
        .then(blur)
        .background(tint, shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val glassRim = Brush.linearGradient(
                listOf(
                    Color(0xFFFFF8EA).copy(alpha = 0.56f),
                    Color(0xFFBEC1B5).copy(alpha = 0.28f),
                    Color(0xFF53645B).copy(alpha = 0.20f),
                    Color(0xFFFFF8EA).copy(alpha = 0.46f),
                ),
            )
            val innerBevel = Brush.linearGradient(
                listOf(
                    Color(0xFFFFFBF3).copy(alpha = 0.34f),
                    Color.Transparent,
                    Color(0xFF68766E).copy(alpha = 0.14f),
                ),
            )
            onDrawWithContent {
                drawContent()
                drawOutline(outline, Color(0xFF405049).copy(alpha = 0.16f), style = Stroke(2.dp.toPx()))
                drawOutline(outline, glassRim, style = Stroke(1.8.dp.toPx()))
                drawOutline(outline, innerBevel, style = Stroke(0.75.dp.toPx()))
            }
        }
}

internal fun LiquidGlassStyle.backdropBlurStyle(): HazeBlurStyle {
    return HazeBlurStyle(
        backgroundColor = Color.Transparent,
        colorEffects = emptyList(),
        blurRadius = backgroundBlurRadius.dp,
        noiseFactor = 0.015f,
        fallbackColorEffect = HazeColorEffect.tint(Color.Transparent),
    )
}
