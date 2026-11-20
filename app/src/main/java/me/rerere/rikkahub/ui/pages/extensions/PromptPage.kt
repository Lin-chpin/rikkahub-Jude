package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.theme.LiquidGlassModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.export.ModeInjectionSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.export.rememberImporter
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookFolder
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PromptPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text(stringResource(R.string.prompt_page_title)) },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    label = { Text(stringResource(R.string.prompt_page_mode_injection_tab)) },
                    icon = { Icon(HugeIcons.MagicWand01, null) },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    label = { Text(stringResource(R.string.prompt_page_lorebook_tab)) },
                    icon = { Icon(HugeIcons.Book01, null) },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ModeInjectionTab(
                    modeInjections = settings.modeInjections,
                    voiceDirectorPrompt = settings.elevenLabsVoiceDirectorPrompt,
                    onUpdate = vm::updateModeInjections,
                    onVoiceDirectorPromptUpdate = {
                        vm.updateSettings(settings.copy(elevenLabsVoiceDirectorPrompt = it))
                    },
                )

                1 -> LorebookTab(
                    lorebooks = settings.lorebooks,
                    folders = settings.lorebookFolders,
                    onUpdate = vm::updateLorebooks,
                )
            }
        }
    }
}

@Composable
private fun ModeInjectionTab(
    modeInjections: List<PromptInjection.ModeInjection>,
    voiceDirectorPrompt: String,
    onUpdate: (List<PromptInjection.ModeInjection>) -> Unit,
    onVoiceDirectorPromptUpdate: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val orderedInjectionsState = remember(modeInjections) { mutableStateOf(modeInjections) }
    val reorderPendingState = remember(modeInjections) { mutableStateOf(false) }
    val currentModeInjections by rememberUpdatedState(orderedInjectionsState.value)
    val currentOnUpdate = rememberUpdatedState(onUpdate)
    val commitReorder by rememberUpdatedState {
        if (reorderPendingState.value) {
            currentOnUpdate.value(orderedInjectionsState.value)
            reorderPendingState.value = false
        }
    }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        reorderModeInjections(orderedInjectionsState.value, from, to)?.let {
            orderedInjectionsState.value = it
            reorderPendingState.value = true
        }
    }
    val editState = useEditState<PromptInjection.ModeInjection> { edited ->
        val current = orderedInjectionsState.value
        val index = current.indexOfFirst { it.id == edited.id }
        val updated = if (index >= 0) {
            current.toMutableList().apply { set(index, edited) }
        } else {
            current + edited
        }
        orderedInjectionsState.value = updated
        currentOnUpdate.value(updated)
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(
        ModeInjectionSerializer,
        listOf(
            "application/json",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ),
    ) { result ->
        result.onSuccess { imported ->
            onUpdate(currentModeInjections + imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false }
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            item(key = "elevenlabs-voice-worldbook") {
                VoiceWorldbookPromptCard(
                    prompt = voiceDirectorPrompt,
                    onSave = onVoiceDirectorPromptUpdate,
                )
            }
            if (orderedInjectionsState.value.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_mode_injection_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                items(orderedInjectionsState.value, key = { it.id }) { injection ->
                    ReorderableItem(
                        state = reorderableState,
                        key = injection.id
                    ) { isDragging ->
                        ModeInjectionCard(
                            injection = injection,
                            modifier = Modifier
                                .longPressDraggableHandle(onDragStopped = commitReorder)
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    }
                                },
                            onEdit = { editState.open(injection) },
                            onDelete = {
                                val updated = orderedInjectionsState.value - injection
                                orderedInjectionsState.value = updated
                                currentOnUpdate.value(updated)
                            }
                        )
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                IconButton(onClick = { importer.importFromFile() }) {
                    Icon(HugeIcons.FileImport, null)
                }
            },
        ) {
            Button(onClick = { editState.open(PromptInjection.ModeInjection()) }) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(HugeIcons.Add01, null)
                    AnimatedVisibility(expanded) {
                        Row {
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.prompt_page_add_mode_injection))
                        }
                    }
                }
            }
        }
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            ModeInjectionEditSheet(
                injection = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun ModeInjectionCard(
    injection: PromptInjection.ModeInjection,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(injection, ModeInjectionSerializer)

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = injection.name.ifEmpty { stringResource(R.string.prompt_page_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Tag(type = TagType.INFO) {
                            Text(getPositionLabel(injection.position))
                        }
                        Tag(type = TagType.DEFAULT) {
                            Text(stringResource(R.string.prompt_page_priority_format, injection.priority))
                        }
                        if (!injection.enabled) {
                            Tag(type = TagType.WARNING) {
                                Text(stringResource(R.string.prompt_page_disabled))
                            }
                        }
                    }
                }
                IconButton(onClick = { showExportDialog = true }) {
                    Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun ModeInjectionEditSheet(
    injection: PromptInjection.ModeInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.ModeInjection) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_mode_injection),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = injection.name,
                    onValueChange = { onEdit(injection.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = injection.enabled,
                            onCheckedChange = { onEdit(injection.copy(enabled = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = injection.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> onEdit(injection.copy(priority = p)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    stringResource(R.string.prompt_page_injection_position),
                    style = MaterialTheme.typography.titleSmall
                )
                InjectionPositionSelector(
                    position = injection.position,
                    onSelect = { onEdit(injection.copy(position = it)) }
                )

                AnimatedVisibility(visible = injection.position == InjectionPosition.AT_DEPTH) {
                    OutlinedTextField(
                        value = injection.injectDepth.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { d -> onEdit(injection.copy(injectDepth = d)) }
                        },
                        label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                AnimatedVisibility(visible = injection.position.usesStandaloneMessage()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.prompt_page_injection_role),
                            style = MaterialTheme.typography.titleSmall
                        )
                        InjectionRoleSelector(
                            role = injection.role,
                            onSelect = { onEdit(injection.copy(role = it)) }
                        )
                    }
                }

                OutlinedTextField(
                    value = injection.content,
                    onValueChange = { onEdit(injection.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    minLines = 5
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}

@Composable
private fun InjectionPositionSelector(
    position: InjectionPosition,
    onSelect: (InjectionPosition) -> Unit
) {
    Select(
        options = InjectionPosition.entries,
        selectedOption = position,
        onOptionSelected = onSelect,
        optionToString = { getPositionLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

private fun InjectionPosition.usesStandaloneMessage(): Boolean = when (this) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT,
    InjectionPosition.AFTER_SYSTEM_PROMPT -> false

    InjectionPosition.TOP_OF_CHAT,
    InjectionPosition.BOTTOM_OF_CHAT,
    InjectionPosition.AT_DEPTH -> true
}

@Composable
private fun getPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_before_system)
    InjectionPosition.AFTER_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_after_system)
    InjectionPosition.TOP_OF_CHAT -> stringResource(R.string.prompt_page_position_top_of_chat)
    InjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.prompt_page_position_bottom_of_chat)
    InjectionPosition.AT_DEPTH -> stringResource(R.string.prompt_page_position_at_depth)
}

@Composable
private fun InjectionRoleSelector(
    role: MessageRole,
    onSelect: (MessageRole) -> Unit
) {
    Select(
        options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
        selectedOption = role,
        onOptionSelected = onSelect,
        optionToString = { getRoleLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun getRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.USER -> stringResource(R.string.prompt_page_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.prompt_page_role_assistant)
    else -> role.name
}

// ==================== Lorebook Tab ====================

@Composable
private fun LorebookTab(
    lorebooks: List<Lorebook>,
    folders: List<LorebookFolder>,
    onUpdate: (List<Lorebook>, List<LorebookFolder>) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val toaster = LocalToaster.current
    val orderedLorebooksState = remember(lorebooks, folders) { mutableStateOf(lorebooks) }
    val orderedFoldersState = remember(lorebooks, folders) { mutableStateOf(folders) }
    val currentLorebooks by rememberUpdatedState(orderedLorebooksState.value)
    val currentFolders by rememberUpdatedState(orderedFoldersState.value)
    val currentOnUpdate = rememberUpdatedState(onUpdate)
    val collapsedFolderIds = remember { mutableStateListOf<String>() }
    var showFolderEditor by remember { mutableStateOf(false) }
    var folderBeingEdited by remember { mutableStateOf<LorebookFolder?>(null) }
    var folderToDelete by remember { mutableStateOf<LorebookFolder?>(null) }
    var bookToMove by remember { mutableStateOf<Lorebook?>(null) }
    var bookSelection by remember { mutableStateOf<LorebookSelection?>(null) }
    var selectedBookIds by remember { mutableStateOf(emptySet<String>()) }
    val reorderPendingState = remember(lorebooks, folders) { mutableStateOf(false) }
    val commitReorder by rememberUpdatedState {
        if (reorderPendingState.value) {
            // Reordering crosses several rows per gesture; save once when dragging ends.
            currentOnUpdate.value(orderedLorebooksState.value, orderedFoldersState.value)
            reorderPendingState.value = false
        }
    }
    val updateBooks: (List<Lorebook>) -> Unit = { updated ->
        orderedLorebooksState.value = updated
        currentOnUpdate.value(updated, orderedFoldersState.value)
    }
    val updateFolders: (List<LorebookFolder>) -> Unit = { updated ->
        orderedFoldersState.value = updated
        currentOnUpdate.value(orderedLorebooksState.value, updated)
    }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        reorderLorebooks(orderedLorebooksState.value, from.key, to.key)?.let {
            orderedLorebooksState.value = it
            reorderPendingState.value = true
        }
    }
    val selectableBooks = when (val selection = bookSelection) {
        null -> emptyList()
        else -> if (selection.sourceFolderId == null) {
            currentLorebooks.filter { book -> currentFolders.none { it.id == book.folderId } }
        } else {
            currentLorebooks.filter { it.folderId?.toString() == selection.sourceFolderId }
        }
    }
    val activeSelection = bookSelection
    val isUngroupedSelectionSource = activeSelection != null && activeSelection.sourceFolderId == null
    val eligibleBookIds = selectableBooks.mapTo(mutableSetOf()) { it.id.toString() }
    val selectedEligibleBookIds = selectedBookIds intersect eligibleBookIds
    val allSelectableBooksSelected = selectableBooks.isNotEmpty() &&
        selectableBooks.all { it.id.toString() in selectedEligibleBookIds }
    val toggleAllSelection = {
        selectedBookIds = if (allSelectableBooksSelected) {
            emptySet()
        } else {
            selectableBooks.mapTo(mutableSetOf()) { it.id.toString() }
        }
    }
    val startBookSelection: (LorebookFolder, String?) -> Unit = { folder, sourceFolderId ->
        bookSelection = LorebookSelection(folder, sourceFolderId)
        selectedBookIds = emptySet()
        if (sourceFolderId != null) collapsedFolderIds.remove(sourceFolderId)
    }
    val editState = useEditState<Lorebook> { edited ->
        val current = orderedLorebooksState.value
        val index = current.indexOfFirst { it.id == edited.id }
        val updated = if (index >= 0) {
            current.toMutableList().apply { set(index, edited) }
        } else {
            current + edited
        }
        updateBooks(updated)
    }
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(LorebookSerializer) { result ->
        result.onSuccess { imported ->
            updateBooks(currentLorebooks + imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false }
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            if (currentLorebooks.isEmpty() && currentFolders.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_lorebook_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                val ungrouped = currentLorebooks.filter { book -> currentFolders.none { it.id == book.folderId } }
                if (ungrouped.isNotEmpty()) {
                    item(key = "folder:ungrouped") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.prompt_page_lorebook_ungrouped),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (isUngroupedSelectionSource) {
                                TextButton(onClick = toggleAllSelection) {
                                    Text(stringResource(if (allSelectableBooksSelected) R.string.prompt_page_clear_selection else R.string.prompt_page_select_all))
                                }
                            }
                        }
                    }
                    items(ungrouped, key = { it.id }) { book ->
                        ReorderableItem(state = reorderableState, key = book.id) { isDragging ->
                            LorebookCard(
                                book = book,
                                modifier = Modifier.longPressDraggableHandle(onDragStopped = commitReorder).graphicsLayer {
                                    if (isDragging) { scaleX = 1.03f; scaleY = 1.03f }
                                },
                                selectionChecked = if (isUngroupedSelectionSource) book.id.toString() in selectedBookIds else null,
                                onToggleSelection = if (isUngroupedSelectionSource) {
                                    { selectedBookIds = if (book.id.toString() in selectedBookIds) selectedBookIds - book.id.toString() else selectedBookIds + book.id.toString() }
                                } else null,
                                onEdit = { editState.open(book) },
                                onDelete = { updateBooks(currentLorebooks.filterNot { it.id == book.id }) },
                                onMove = { bookToMove = book },
                            )
                        }
                    }
                } else if (isUngroupedSelectionSource) {
                    item(key = "folder:ungrouped-empty") {
                        Text(
                            text = stringResource(R.string.prompt_page_no_lorebooks_to_add),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
                        )
                    }
                }
                currentFolders.forEach { folder ->
                    val folderBooks = currentLorebooks.filter { it.folderId == folder.id }
                    val isSelectionSource = bookSelection?.sourceFolderId == folder.id.toString()
                    item(key = "folder:${folder.id}") {
                        LorebookFolderHeader(
                            folder = folder,
                            count = folderBooks.size,
                            expanded = folder.id.toString() !in collapsedFolderIds,
                            isSelectionSource = isSelectionSource,
                            allSelected = allSelectableBooksSelected,
                            modifier = Modifier,
                            onToggle = {
                                val id = folder.id.toString()
                                if (id in collapsedFolderIds) collapsedFolderIds.remove(id)
                                else collapsedFolderIds.add(id)
                            },
                            onAddBooks = { startBookSelection(folder, null) },
                            onSelectAll = toggleAllSelection,
                            onRemoveBooks = { startBookSelection(folder, folder.id.toString()) },
                            onRename = {
                                folderBeingEdited = folder
                                showFolderEditor = true
                            },
                            onDelete = { folderToDelete = folder },
                        )
                    }
                    if (folder.id.toString() !in collapsedFolderIds) {
                        if (folderBooks.isEmpty()) {
                            item(key = "folder-empty:${folder.id}") {
                                Text(
                                    text = stringResource(R.string.prompt_page_lorebook_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 8.dp)
                                )
                            }
                        } else {
                            items(folderBooks, key = { it.id }) { book ->
                                ReorderableItem(state = reorderableState, key = book.id) { isDragging ->
                                    LorebookCard(
                                        book = book,
                                        modifier = Modifier.longPressDraggableHandle(onDragStopped = commitReorder).graphicsLayer {
                                            if (isDragging) { scaleX = 1.03f; scaleY = 1.03f }
                                        },
                                        selectionChecked = if (isSelectionSource) book.id.toString() in selectedBookIds else null,
                                        onToggleSelection = if (isSelectionSource) {
                                            { selectedBookIds = if (book.id.toString() in selectedBookIds) selectedBookIds - book.id.toString() else selectedBookIds + book.id.toString() }
                                        } else null,
                                        onEdit = { editState.open(book) },
                                        onDelete = { updateBooks(currentLorebooks.filterNot { it.id == book.id }) },
                                        onMove = { bookToMove = book },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            leadingContent = {
                if (bookSelection == null) {
                    IconButton(onClick = { importer.importFromFile() }) {
                        Icon(HugeIcons.FileImport, null)
                    }
                }
            },
        ) {
            val selection = bookSelection
            if (selection == null) {
                Button(onClick = {
                    folderBeingEdited = null
                    showFolderEditor = true
                }) {
                    Text(stringResource(R.string.prompt_page_add_folder))
                }
                Button(onClick = { editState.open(Lorebook()) }) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(HugeIcons.Add01, null)
                        AnimatedVisibility(expanded) {
                            Row {
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.prompt_page_add_lorebook))
                            }
                        }
                    }
                }
            } else {
                TextButton(onClick = {
                    bookSelection = null
                    selectedBookIds = emptySet()
                }) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                Button(
                    onClick = {
                        val destination = if (selection.sourceFolderId == null) selection.targetFolder.id else null
                        updateBooks(assignLorebooksToFolder(currentLorebooks, selectedEligibleBookIds, destination))
                        bookSelection = null
                        selectedBookIds = emptySet()
                    },
                    enabled = selectedEligibleBookIds.isNotEmpty(),
                ) {
                    Text(
                        stringResource(
                            if (selection.sourceFolderId == null) R.string.prompt_page_add_selected_to_folder
                            else R.string.prompt_page_remove_selected_from_folder,
                            selectedEligibleBookIds.size,
                            currentFolders.firstOrNull { it.id == selection.targetFolder.id }?.name ?: selection.targetFolder.name,
                        )
                    )
                }
            }
        }
    }

    if (showFolderEditor) {
        LorebookFolderDialog(
            initialName = folderBeingEdited?.name.orEmpty(),
            isEditing = folderBeingEdited != null,
            onDismiss = { showFolderEditor = false },
            onSave = { name ->
                val current = folderBeingEdited
                if (current == null) updateFolders(currentFolders + LorebookFolder(name = name))
                else updateFolders(currentFolders.map { if (it.id == current.id) it.copy(name = name) else it })
                showFolderEditor = false
            }
        )
    }

    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.prompt_page_delete_folder)) },
            text = { Text(stringResource(R.string.prompt_page_delete_folder_message, folder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    orderedLorebooksState.value = currentLorebooks.map { if (it.folderId == folder.id) it.copy(folderId = null) else it }
                    orderedFoldersState.value = currentFolders.filterNot { it.id == folder.id }
                    currentOnUpdate.value(orderedLorebooksState.value, orderedFoldersState.value)
                    if (bookSelection?.targetFolder?.id == folder.id || bookSelection?.sourceFolderId == folder.id.toString()) {
                        bookSelection = null
                        selectedBookIds = emptySet()
                    }
                    folderToDelete = null
                }) { Text(stringResource(R.string.prompt_page_delete)) }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text(stringResource(R.string.prompt_page_cancel)) } }
        )
    }

    bookToMove?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToMove = null },
            title = { Text(stringResource(R.string.prompt_page_move_to_folder)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    TextButton(onClick = {
                        updateBooks(currentLorebooks.map { if (it.id == book.id) it.copy(folderId = null) else it })
                        bookToMove = null
                    }) { Text(stringResource(R.string.prompt_page_lorebook_ungrouped)) }
                    currentFolders.forEach { folder ->
                        TextButton(onClick = {
                            updateBooks(currentLorebooks.map { if (it.id == book.id) it.copy(folderId = folder.id) else it })
                            bookToMove = null
                        }) { Text(folder.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { bookToMove = null }) { Text(stringResource(R.string.prompt_page_cancel)) } }
        )
    }

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            LorebookEditSheet(
                book = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun LorebookCard(
    book: Lorebook,
    modifier: Modifier = Modifier,
    selectionChecked: Boolean? = null,
    onToggleSelection: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(book, LorebookSerializer)

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { swipeState.reset() } }) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledIconButton(onClick = {
                    scope.launch {
                        onDelete()
                        swipeState.reset()
                    }
                }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionChecked != null && onToggleSelection != null) {
                    Checkbox(
                        checked = selectionChecked,
                        onCheckedChange = { onToggleSelection() },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = book.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_lorebook) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (book.description.isNotEmpty()) {
                        Text(
                            text = book.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Tag(type = TagType.INFO) {
                            Text(
                                stringResource(
                                    R.string.prompt_page_entries_count_format,
                                    book.entries.size
                                )
                            )
                        }
                        if (!book.enabled) {
                            Tag(type = TagType.WARNING) {
                                Text(stringResource(R.string.prompt_page_disabled))
                            }
                        }
                    }
                }
                if (selectionChecked == null) {
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(HugeIcons.Share03, stringResource(R.string.export_title))
                    }
                    TextButton(onClick = onMove) { Text(stringResource(R.string.prompt_page_folder)) }
                    IconButton(onClick = onEdit) {
                        Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun LorebookFolderHeader(
    folder: LorebookFolder,
    count: Int,
    expanded: Boolean,
    isSelectionSource: Boolean,
    allSelected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onAddBooks: () -> Unit,
    onSelectAll: () -> Unit,
    onRemoveBooks: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(folder.id) { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                HugeIcons.ArrowDown01,
                contentDescription = stringResource(if (expanded) R.string.prompt_page_collapse else R.string.prompt_page_expand),
                modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 0f else -90f },
            )
        }
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.prompt_page_folder_count_format, count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isSelectionSource && count > 0) {
            TextButton(onClick = onSelectAll) {
                Text(stringResource(if (allSelected) R.string.prompt_page_clear_selection else R.string.prompt_page_select_all))
            }
        }
        IconButton(onClick = onAddBooks) {
            Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add_books_to_folder))
        }
        Box {
            TextButton(onClick = { menuExpanded = true }) { Text(stringResource(R.string.prompt_page_edit)) }
            androidx.compose.material3.DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.prompt_page_rename_folder)) },
                    onClick = { menuExpanded = false; onRename() },
                )
                if (count > 0) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.prompt_page_select_books_to_remove)) },
                        onClick = { menuExpanded = false; onRemoveBooks() },
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.prompt_page_delete_folder)) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

private data class LorebookSelection(
    val targetFolder: LorebookFolder,
    val sourceFolderId: String?,
)

@Composable
private fun LorebookFolderDialog(
    initialName: String,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditing) R.string.prompt_page_rename_folder else R.string.prompt_page_add_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.prompt_page_folder_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.prompt_page_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_page_cancel)) } },
    )
}

@Composable
private fun LorebookEditSheet(
    book: Lorebook,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (Lorebook) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val entryEditState = useEditState<PromptInjection.RegexInjection> { edited ->
        val index = book.entries.indexOfFirst { it.id == edited.id }
        if (index >= 0) {
            onEdit(book.copy(entries = book.entries.toMutableList().apply { set(index, edited) }))
        } else {
            onEdit(book.copy(entries = book.entries + edited))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_lorebook),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = book.name,
                    onValueChange = { onEdit(book.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = book.description,
                    onValueChange = { onEdit(book.copy(description = it)) },
                    label = { Text(stringResource(R.string.prompt_page_description)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = book.enabled,
                            onCheckedChange = { onEdit(book.copy(enabled = it)) }
                        )
                    }
                )

                // 条目列表
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.prompt_page_entries_format, book.entries.size),
                        style = MaterialTheme.typography.titleSmall
                    )
                    IconButton(onClick = {
                        entryEditState.open(PromptInjection.RegexInjection())
                    }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add_entry))
                    }
                }

                book.entries.forEach { entry ->
                    RegexInjectionEntryCard(
                        entry = entry,
                        onEdit = { entryEditState.open(entry) },
                        onDelete = {
                            onEdit(book.copy(entries = book.entries - entry))
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }

    if (entryEditState.isEditing) {
        entryEditState.currentState?.let { state ->
            RegexInjectionEditDialog(
                entry = state,
                onDismiss = { entryEditState.dismiss() },
                onConfirm = { entryEditState.confirm() },
                onEdit = { entryEditState.currentState = it }
            )
        }
    }
}

@Composable
private fun RegexInjectionEntryCard(
    entry: PromptInjection.RegexInjection,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = entry.name.ifEmpty { stringResource(R.string.prompt_page_unnamed_entry) },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (entry.keywords.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.prompt_page_keywords_format, entry.keywords.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!entry.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.prompt_page_disabled))
                        }
                    }
                }
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegexInjectionEditDialog(
    entry: PromptInjection.RegexInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.RegexInjection) -> Unit
) {
    var newKeyword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_page_edit_entry)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = entry.name,
                    onValueChange = { onEdit(entry.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { onEdit(entry.copy(enabled = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = entry.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> onEdit(entry.copy(priority = p)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    stringResource(R.string.prompt_page_injection_position),
                    style = MaterialTheme.typography.titleSmall
                )
                InjectionPositionSelector(
                    position = entry.position,
                    onSelect = { onEdit(entry.copy(position = it)) }
                )

                AnimatedVisibility(visible = entry.position == InjectionPosition.AT_DEPTH) {
                    OutlinedTextField(
                        value = entry.injectDepth.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { d -> onEdit(entry.copy(injectDepth = d)) }
                        },
                        label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                // 关键词
                Text(stringResource(R.string.prompt_page_keywords_label), style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    entry.keywords.forEach { keyword ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(keyword) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        onEdit(entry.copy(keywords = entry.keywords - keyword))
                                    },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                                }
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newKeyword,
                        onValueChange = { newKeyword = it },
                        label = { Text(stringResource(R.string.prompt_page_new_keyword)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (newKeyword.isNotBlank()) {
                                onEdit(entry.copy(keywords = entry.keywords + newKeyword.trim()))
                                newKeyword = ""
                            }
                        }
                    ) {
                        Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
                    }
                }

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_use_regex)) },
                    tail = {
                        Switch(
                            checked = entry.useRegex,
                            onCheckedChange = { onEdit(entry.copy(useRegex = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_case_sensitive)) },
                    tail = {
                        Switch(
                            checked = entry.caseSensitive,
                            onCheckedChange = { onEdit(entry.copy(caseSensitive = it)) }
                        )
                    }
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_constant_active)) },
                    description = { Text(stringResource(R.string.prompt_page_constant_active_desc)) },
                    tail = {
                        Switch(
                            checked = entry.constantActive,
                            onCheckedChange = { onEdit(entry.copy(constantActive = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = entry.scanDepth.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { d -> onEdit(entry.copy(scanDepth = d)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_scan_depth)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                AnimatedVisibility(visible = entry.position.usesStandaloneMessage()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.prompt_page_injection_role),
                            style = MaterialTheme.typography.titleSmall
                        )
                        InjectionRoleSelector(
                            role = entry.role,
                            onSelect = { onEdit(entry.copy(role = it)) }
                        )
                    }
                }

                OutlinedTextField(
                    value = entry.content,
                    onValueChange = { onEdit(entry.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    minLines = 4
                )
            }
        },
        confirmButton = {
            val canSave = entry.keywords.isNotEmpty() || entry.constantActive
            TextButton(
                onClick = onConfirm,
                enabled = canSave
            ) {
                Text(stringResource(R.string.prompt_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.prompt_page_cancel))
            }
        }
    )
}
