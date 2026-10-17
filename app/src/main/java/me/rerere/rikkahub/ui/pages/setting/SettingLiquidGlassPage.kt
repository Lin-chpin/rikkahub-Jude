package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.*
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun SettingLiquidGlassPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var opacity by remember(settings.liquidGlassOpacity) { mutableFloatStateOf(settings.liquidGlassOpacity.toFloat()) }
    var blur by remember(settings.liquidGlassBlur) { mutableFloatStateOf(settings.liquidGlassBlur.toFloat()) }

    var workspaceOpacity by remember(settings.liquidGlassWorkspaceOpacity) { mutableFloatStateOf(settings.liquidGlassWorkspaceOpacity.toFloat()) }
    var workspaceBlur by remember(settings.liquidGlassWorkspaceBlur) { mutableFloatStateOf(settings.liquidGlassWorkspaceBlur.toFloat()) }
    val saveGlassSettings = {
        vm.updateSettings(settings.copy(
            liquidGlassOpacity = opacity.roundToInt(),
            liquidGlassBlur = blur.roundToInt(),
            liquidGlassWorkspaceOpacity = workspaceOpacity.roundToInt(),
            liquidGlassWorkspaceBlur = workspaceBlur.roundToInt(),
        ))
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.liquid_glass_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.liquid_glass_title)) },
                trailingContent = {
                    Switch(
                        checked = settings.liquidGlassEnabled,
                        onCheckedChange = { vm.updateSettings(settings.copy(liquidGlassEnabled = it)) },
                    )
                },
                colors = CustomColors.listItemColors,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidGlassBubbleSettingsPreview(settings, opacity / 100f, blur)
                LiquidGlassControls(
                    title = stringResource(R.string.liquid_glass_other_group),
                    opacity = opacity,
                    blur = blur,
                    enabled = settings.liquidGlassEnabled,
                    onOpacityChange = { opacity = it },
                    onBlurChange = { blur = it },
                    onChangeFinished = saveGlassSettings,
                )
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidGlassWorkspaceSettingsPreview(settings, workspaceOpacity / 100f, workspaceBlur)
                LiquidGlassControls(
                    title = stringResource(R.string.liquid_glass_workspace_group),
                    opacity = workspaceOpacity,
                    blur = workspaceBlur,
                    enabled = settings.liquidGlassEnabled,
                    onOpacityChange = { workspaceOpacity = it },
                    onBlurChange = { workspaceBlur = it },
                    onChangeFinished = saveGlassSettings,
                )
            }
            if (Build.VERSION.SDK_INT < 31) {
                Text(stringResource(R.string.liquid_glass_blur_unsupported), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LiquidGlassControls(
    title: String,
    opacity: Float,
    blur: Float,
    enabled: Boolean,
    onOpacityChange: (Float) -> Unit,
    onBlurChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.liquid_glass_opacity_title))
            Text("${opacity.roundToInt()}%")
        }
        Slider(
            value = opacity,
            onValueChange = onOpacityChange,
            onValueChangeFinished = onChangeFinished,
            enabled = enabled,
            valueRange = 0f..100f,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.liquid_glass_blur_title))
            Text("${(blur / 60f * 100f).roundToInt()}%")
        }
        Slider(
            value = blur,
            onValueChange = onBlurChange,
            onValueChangeFinished = onChangeFinished,
            enabled = enabled && Build.VERSION.SDK_INT >= 31,
            valueRange = 0f..60f,
        )
    }
}
