package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.R

private data class CodexTutorialStep(
    val titleRes: Int,
    val bodyRes: Int,
    val imageRes: Int? = null,
    val imageDescriptionRes: Int? = null,
)

private val codexTutorialSteps = listOf(
    CodexTutorialStep(
        titleRes = R.string.setting_provider_page_codex_tutorial_step_1_title,
        bodyRes = R.string.setting_provider_page_codex_tutorial_step_1_body,
    ),
    CodexTutorialStep(
        titleRes = R.string.setting_provider_page_codex_tutorial_step_2_title,
        bodyRes = R.string.setting_provider_page_codex_tutorial_step_2_body,
        imageRes = R.drawable.codex_tutorial_device_code,
        imageDescriptionRes = R.string.setting_provider_page_codex_tutorial_step_2_image,
    ),
    CodexTutorialStep(
        titleRes = R.string.setting_provider_page_codex_tutorial_step_3_title,
        bodyRes = R.string.setting_provider_page_codex_tutorial_step_3_body,
        imageRes = R.drawable.codex_tutorial_other_method,
        imageDescriptionRes = R.string.setting_provider_page_codex_tutorial_step_3_image,
    ),
    CodexTutorialStep(
        titleRes = R.string.setting_provider_page_codex_tutorial_step_4_title,
        bodyRes = R.string.setting_provider_page_codex_tutorial_step_4_body,
        imageRes = R.drawable.codex_tutorial_auth_method,
        imageDescriptionRes = R.string.setting_provider_page_codex_tutorial_step_4_image,
    ),
    CodexTutorialStep(
        titleRes = R.string.setting_provider_page_codex_tutorial_step_5_title,
        bodyRes = R.string.setting_provider_page_codex_tutorial_step_5_body,
        imageRes = R.drawable.codex_tutorial_email_code,
        imageDescriptionRes = R.string.setting_provider_page_codex_tutorial_step_5_image,
    ),
)

@Composable
fun CodexLoginTutorialDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onLogin: () -> Unit,
) {
    if (!visible) return

    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()
    val step = codexTutorialSteps[currentStep]
    val isLastStep = currentStep == codexTutorialSteps.lastIndex
    val readToEnd = scrollState.value >= scrollState.maxValue

    LaunchedEffect(visible) {
        if (visible) currentStep = 0
    }
    LaunchedEffect(currentStep) {
        scrollState.scrollTo(0)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                TopAppBar(
                    title = { Text(stringResource(R.string.setting_provider_page_codex_tutorial_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                        }
                    },
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_codex_tutorial_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        codexTutorialSteps.indices.forEach { index ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (index <= currentStep) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.setting_provider_page_codex_tutorial_step_label,
                            currentStep + 1,
                            codexTutorialSteps.size,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = stringResource(step.titleRes),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(step.bodyRes),
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    step.imageRes?.let { imageRes ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 2.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Image(
                                painter = painterResource(imageRes),
                                contentDescription = step.imageDescriptionRes?.let { stringResource(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 390.dp)
                                    .padding(8.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }

                    if (currentStep == 0) {
                        TutorialHint(
                            titleRes = R.string.setting_provider_page_codex_tutorial_vpn_title,
                            bodyRes = R.string.setting_provider_page_codex_tutorial_vpn_body,
                            warning = true,
                        )
                    }

                    if (currentStep == 1) {
                        Text(
                            text = stringResource(R.string.setting_provider_page_codex_tutorial_step_2_path),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    if (isLastStep) {
                        TutorialHint(
                            titleRes = R.string.setting_provider_page_codex_tutorial_code_warning_title,
                            bodyRes = R.string.setting_provider_page_codex_tutorial_code_warning_body,
                            warning = true,
                        )
                        if (readToEnd) {
                            Text(
                                text = stringResource(R.string.setting_provider_page_codex_tutorial_ready),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    Spacer(Modifier.size(4.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { currentStep-- },
                            enabled = currentStep > 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.setting_provider_page_codex_tutorial_previous))
                        }
                        Button(
                            onClick = {
                                if (isLastStep) onLogin() else currentStep++
                            },
                            enabled = !isLastStep || readToEnd,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (isLastStep) R.string.setting_provider_page_codex_tutorial_login
                                    else R.string.setting_provider_page_codex_tutorial_next,
                                )
                            )
                        }
                    }
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setting_provider_page_codex_tutorial_skip))
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialHint(
    titleRes: Int,
    bodyRes: Int,
    warning: Boolean,
) {
    val background = if (warning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (warning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = background,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = if (warning) "!" else "✓",
                color = content,
                style = MaterialTheme.typography.titleMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(titleRes),
                    color = content,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(bodyRes),
                    color = content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
