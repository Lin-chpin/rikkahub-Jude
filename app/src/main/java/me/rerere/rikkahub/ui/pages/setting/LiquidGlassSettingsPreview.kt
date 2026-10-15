package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import me.rerere.rikkahub.ui.theme.*

@Composable
internal fun LiquidGlassBubbleSettingsPreview(settings: Settings, opacity: Float, blur: Float) {
    LiquidGlassPreviewScene(settings, opacity, blur) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PreviewGlassSurface {
                Text(stringResource(R.string.liquid_glass_preview_assistant), Modifier.padding(14.dp))
            }
            Box(Modifier.align(Alignment.End)) {
                PreviewGlassSurface {
                    Text(stringResource(R.string.liquid_glass_preview_user), Modifier.padding(14.dp))
                }
            }
        }
    }
}

@Composable
internal fun LiquidGlassWorkspaceSettingsPreview(settings: Settings, opacity: Float, blur: Float) {
    LiquidGlassPreviewScene(settings, opacity, blur, workspace = true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewGlassSurface(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_input_placeholder), Modifier.padding(16.dp))
            }
            PreviewGlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(R.string.take_picture, R.string.photo, R.string.upload_file).forEach { label ->
                            PreviewGlassSurface {
                                Text(stringResource(label), Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                            }
                        }
                    }
                    PreviewGlassSurface(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.mcp_picker_title), Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassPreviewScene(
    settings: Settings,
    opacity: Float,
    blur: Float,
    workspace: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = rememberHazeState()
    LiquidGlassScene(
        enabled = settings.liquidGlassEnabled,
        blurEnabled = Build.VERSION.SDK_INT >= 31,
        opacity = opacity,
        blurRadius = blur,
        workspaceOpacity = opacity,
        workspaceBlurRadius = blur,
    ) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background).hazeSource(backdrop)) {
                AssistantBackground(setting = settings, modifier = Modifier.fillMaxSize())
            }
            val glass = if (workspace) LocalLiquidGlassWorkspace.current else LocalLiquidGlass.current
            CompositionLocalProvider(
                LocalLiquidGlass provides glass,
                LocalLiquidGlassHaze provides backdrop,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun PreviewGlassSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val enabled = LocalLiquidGlass.current.enabled
    Surface(
        modifier = modifier.then(if (enabled) Modifier.liquidGlassChrome(shape) else Modifier),
        shape = shape,
        color = if (enabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}
