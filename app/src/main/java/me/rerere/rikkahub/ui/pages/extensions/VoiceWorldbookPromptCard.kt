package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
internal fun VoiceWorldbookPromptCard(
    prompt: String,
    onSave: (String) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.prompt_page_voice_worldbook_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Tag(type = TagType.INFO) {
                        Text(stringResource(R.string.prompt_page_voice_worldbook_pinned))
                    }
                }
                Text(
                    text = stringResource(R.string.prompt_page_voice_worldbook_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { editing = true }) {
                Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
            }
        }
    }

    if (editing) {
        VoiceWorldbookPromptEditor(
            prompt = prompt,
            onDismiss = { editing = false },
            onSave = {
                onSave(it)
                editing = false
            },
        )
    }
}

@Composable
private fun VoiceWorldbookPromptEditor(
    prompt: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(prompt) { mutableStateOf(prompt) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.prompt_page_voice_worldbook_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                modifier = Modifier.fillMaxWidth().height(360.dp),
                minLines = 8,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                }) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = {
                    scope.launch {
                        sheetState.hide()
                        onSave(draft)
                    }
                }) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}
