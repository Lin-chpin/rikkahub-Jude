package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.blur.HazeColorEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings

class LiquidGlassStyleTest {
    @Test
    fun opacityAndBlurAreIndependentAndHazeOnlyBlurs() {
        val glass = LiquidGlassStyle(enabled = true, blurEnabled = true, opacity = 0.2f, blurRadius = 24f)
        val initial = glass.backdropBlurStyle()
        val opaque = glass.copy(opacity = 0.8f).backdropBlurStyle()
        val blurred = glass.copy(blurRadius = 60f).backdropBlurStyle()

        assertEquals(14.4f, initial.blurRadius.value, 0.001f)
        assertEquals(Color.Transparent, initial.backgroundColor)
        assertTrue(initial.colorEffects.isEmpty())
        assertEquals(HazeColorEffect.tint(Color.Transparent), initial.fallbackColorEffect)
        assertEquals(initial.blurRadius, opaque.blurRadius)
        assertTrue(opaque.colorEffects.isEmpty())
        assertEquals(initial.colorEffects, blurred.colorEffects)
        assertEquals(36.dp, blurred.blurRadius)
        assertEquals(0.dp, glass.copy(blurRadius = 0f).backdropBlurStyle().blurRadius)
        assertEquals(0.dp, glass.copy(blurRadius = -1f).backdropBlurStyle().blurRadius)
        assertEquals(36.dp, glass.copy(blurRadius = 100f).backdropBlurStyle().blurRadius)
        val clear = glass.copy(opacity = 0f, blurRadius = 0f).backdropBlurStyle()
        assertEquals(0f, glass.copy(opacity = 0f).surfaceOpacity, 0f)
        assertTrue(clear.colorEffects.isEmpty())
        assertEquals(HazeColorEffect.tint(Color.Transparent), clear.fallbackColorEffect)
    }

    @Test
    fun oldSettingsSeedWorkspaceValuesAndThenBothGroupsRemainIndependent() {
        val old = Json.decodeFromString<Settings>("""{"liquidGlassOpacity":30,"liquidGlassBlur":18}""")
        assertEquals(30, old.liquidGlassWorkspaceOpacity)
        assertEquals(18, old.liquidGlassWorkspaceBlur)
        val changed = old.copy(liquidGlassWorkspaceOpacity = 80, liquidGlassWorkspaceBlur = 45)
        val restored = Json.decodeFromString<Settings>(Json.encodeToString(changed))
        assertEquals(30, restored.liquidGlassOpacity)
        assertEquals(18, restored.liquidGlassBlur)
        assertEquals(80, restored.liquidGlassWorkspaceOpacity)
        assertEquals(45, restored.liquidGlassWorkspaceBlur)
    }
}
