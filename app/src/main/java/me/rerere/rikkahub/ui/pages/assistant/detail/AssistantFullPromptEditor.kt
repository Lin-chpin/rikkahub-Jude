package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
internal fun AssistantFullPromptEditor(
    prompt: String,
    savedPrompt: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val editorState = rememberTextFieldState(initialText = prompt)
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestClose = {
        if (editorState.text.toString() == savedPrompt) onCancel() else confirmDiscard = true
    }

    BackHandler(enabled = !confirmDiscard, onBack = requestClose)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_prompt_editor_title)) },
                navigationIcon = {
                    TextButton(onClick = requestClose) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(editorState.text.toString()) },
                    ) {
                        Text(stringResource(R.string.text_area_save))
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.assistant_prompt_editor_hint, editorState.text.length),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                state = editorState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                lineLimits = TextFieldLineLimits.MultiLine(
                    minHeightInLines = 12,
                    maxHeightInLines = 60,
                ),
            )
        }
    }

    RikkaConfirmDialog(
        show = confirmDiscard,
        title = stringResource(R.string.assistant_prompt_editor_discard_title),
        confirmText = stringResource(R.string.assistant_prompt_editor_discard),
        dismissText = stringResource(R.string.cancel),
        onConfirm = onCancel,
        onDismiss = { confirmDiscard = false },
        text = { Text(stringResource(R.string.assistant_prompt_editor_discard_message)) },
    )
}
