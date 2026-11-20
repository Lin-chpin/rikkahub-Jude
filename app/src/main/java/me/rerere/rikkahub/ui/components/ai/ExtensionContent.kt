package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookFolder
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage

@Composable
fun ModeInjectionsContent(
    modeInjections: List<PromptInjection.ModeInjection>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(modeInjections) { injection ->
            ListItem(
                headlineContent = {
                    Text(injection.name.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(injection.id),
                        onCheckedChange = { checked -> onToggle(injection.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun LorebooksContent(
    lorebooks: List<Lorebook>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
    folders: List<LorebookFolder> = emptyList(),
    onSelectFolder: (Set<kotlin.uuid.Uuid>) -> Unit = {},
) {
    val booksByFolder = remember(lorebooks) { lorebooks.groupBy { it.folderId } }
    val folderIds = remember(folders) { folders.mapTo(hashSetOf()) { it.id } }
    val uncategorizedBooks = lorebooks.filter { it.folderId !in folderIds }
    val expandedFolders = remember { mutableStateMapOf<kotlin.uuid.Uuid, Boolean>() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(uncategorizedBooks, key = { "lorebook:${it.id}" }) { lorebook ->
            LorebookItem(lorebook, selectedIds, onToggle)
        }

        folders.forEach { folder ->
            val folderBooks = booksByFolder[folder.id].orEmpty()
            val expanded = expandedFolders[folder.id] ?: true
            item(key = "folder:${folder.id}") {
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
                        .clickable { onSelectFolder(folderBooks.mapTo(linkedSetOf()) { it.id }) },
                    leadingContent = {
                        Icon(
                            imageVector = if (expanded) Lucide.FolderOpen else Lucide.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = {
                        Text(folder.name.ifBlank { stringResource(R.string.prompt_page_folder) })
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.prompt_page_folder_count_format, folderBooks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { expandedFolders[folder.id] = !expanded }) {
                            Icon(
                                HugeIcons.ArrowDown01,
                                contentDescription = stringResource(
                                    if (expanded) R.string.prompt_page_collapse else R.string.prompt_page_expand
                                ),
                                modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 0f else -90f },
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (expanded) {
                if (folderBooks.isEmpty()) {
                    item(key = "folder-empty:${folder.id}") {
                        Text(
                            text = stringResource(R.string.prompt_page_lorebook_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 8.dp),
                        )
                    }
                } else {
                    items(folderBooks, key = { "lorebook:${it.id}" }) { lorebook ->
                        LorebookItem(lorebook, selectedIds, onToggle)
                    }
                }
            }
        }

        if (onManage != null) {
            item(key = "manage") {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
private fun LorebookItem(
    lorebook: Lorebook,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(lorebook.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) })
        },
        supportingContent = if (lorebook.description.isNotBlank()) {
            {
                Text(
                    text = lorebook.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else null,
        trailingContent = {
            Switch(
                checked = selectedIds.contains(lorebook.id),
                onCheckedChange = { checked -> onToggle(lorebook.id, checked) }
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun SkillsContent(
    skills: List<SkillMetadata>,
    enabledSkills: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(skills, key = { it.name }) { skill ->
            ListItem(
                headlineContent = { Text(skill.name) },
                supportingContent = if (skill.description.isNotBlank()) {
                    {
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = enabledSkills.contains(skill.name),
                        onCheckedChange = { checked -> onToggle(skill.name, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun QuickMessagesContent(
    quickMessages: List<QuickMessage>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(quickMessages, key = { it.id }) { quickMessage ->
            ListItem(
                headlineContent = {
                    Text(quickMessage.title.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                supportingContent = if (quickMessage.content.isNotBlank()) {
                    {
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(quickMessage.id),
                        onCheckedChange = { checked -> onToggle(quickMessage.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.extension_content_manage),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
fun ExtensionEmptyState(
    message: String,
    buttonText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (buttonText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Icon(HugeIcons.Link01, contentDescription = null)
                Text(buttonText)
            }
        }
    }
}
