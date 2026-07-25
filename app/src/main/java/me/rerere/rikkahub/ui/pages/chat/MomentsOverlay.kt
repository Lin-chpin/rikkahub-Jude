package me.rerere.rikkahub.ui.pages.chat

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.Moment
import me.rerere.rikkahub.data.repository.MomentAuthor
import me.rerere.rikkahub.data.repository.MomentComment
import me.rerere.rikkahub.data.repository.MomentEntry
import me.rerere.rikkahub.data.repository.MomentProfile
import me.rerere.rikkahub.data.repository.MomentRepository
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import java.util.Calendar
import kotlin.uuid.Uuid

@Composable
fun MomentsOverlay(
    visible: Boolean,
    assistantId: Uuid,
    assistant: Assistant,
    conversation: Conversation,
    assistantName: String,
    conversationSystemPrompt: String?,
    settings: Settings,
    vm: MomentsVM,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val scope = rememberCoroutineScope()
    val timeline by remember(assistantId) {
        vm.observeTimeline(assistantId)
    }.collectAsStateWithLifecycle(emptyList())
    val profile by remember(assistantId) {
        vm.observeProfile(assistantId)
    }.collectAsStateWithLifecycle(MomentProfile(assistantId))
    val processing by vm.processing.collectAsStateWithLifecycle()
    var composerVisible by remember { mutableStateOf(false) }
    var selectedDayStartMillis by remember(assistantId) { mutableStateOf<Long?>(null) }
    val visibleTimeline = remember(timeline, selectedDayStartMillis) {
        selectedDayStartMillis?.let { start ->
            val end = start + DAY_MILLIS
            timeline.filter { it.moment.createdAt in start until end }
        } ?: timeline
    }
    val context = LocalContext.current

    LaunchedEffect(vm, context) {
        vm.visionModelSetupRequired.collect {
            Toast.makeText(context, R.string.moments_vision_model_required, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(vm, context) {
        vm.refreshReports.collect { report ->
            Toast.makeText(context, report.toUserMessage(context), Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(assistantId, conversation.compressedSummary) {
        vm.markViewed(assistantId)
        vm.processDue(assistantId, assistant, conversation, conversationSystemPrompt)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        val isDark = isSystemInDarkTheme()
        val timelineColor = if (isDark) Color.Black else Color(0xFFFFF7ED)
        val userName = settings.displaySetting.userNickname
            .ifBlank { androidx.compose.ui.res.stringResource(R.string.user_default_name) }
        val userAvatar = settings.displaySetting.userAvatar

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = timelineColor,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(timelineColor),
                ) {
                    item {
                        MomentsHeader(
                            profile = profile,
                            userName = userName,
                            userAvatar = userAvatar,
                            processing = processing,
                            onDismiss = onDismiss,
                            onRefresh = {
                                vm.processDue(assistantId, assistant, conversation, conversationSystemPrompt, manual = true)
                                vm.markViewed(assistantId)
                            },
                            onPost = {
                                composerVisible = true
                            },
                            onCalendar = {
                                showMomentDatePicker(context, selectedDayStartMillis) {
                                    selectedDayStartMillis = it.takeUnless { dayStart ->
                                        dayStart == currentDayStartMillis()
                                    }
                                }
                            },
                            onCoverSelected = { uri ->
                                scope.launch {
                                    val local = vm.filesManager.createChatFilesByContents(listOf(uri))
                                    local.firstOrNull()?.let { vm.updateCover(assistantId, it.toString()) }
                                }
                            },
                        )
                    }
                    selectedDayStartMillis?.let { dayStart ->
                        item {
                            MomentDateFilterBar(
                                label = momentDayLabel(dayStart),
                                onClear = { selectedDayStartMillis = null },
                            )
                        }
                    }
                    if (visibleTimeline.isEmpty()) {
                        item {
                            EmptyMomentsState(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 80.dp)
                            )
                        }
                    } else {
                        items(
                            items = visibleTimeline,
                            key = { it.moment.id.toString() }
                        ) { entry ->
                            MomentItem(
                                entry = entry,
                                assistant = assistant,
                                assistantName = assistantName,
                                userName = userName,
                                userAvatar = userAvatar,
                                onLike = { vm.toggleUserLike(entry.moment.id) },
                                onComment = { content -> vm.addComment(entry.moment.id, content) },
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    if (composerVisible) {
        MomentComposerDialog(
            onDismiss = { composerVisible = false },
            onSubmit = { content, imageUris ->
                vm.postUserMoment(assistantId, content, imageUris)
                composerVisible = false
            },
            saveImages = { uris ->
                vm.filesManager.createChatFilesByContents(uris)
                    .take(MomentRepository.MAX_IMAGES)
                    .map { it.toString() }
            }
        )
    }
}

private fun MomentsRefreshReport.toUserMessage(context: Context): String {
    if (processingSkipped) {
        return context.getString(R.string.moments_refresh_processing)
    }
    if (candidateCount == 0) {
        return context.getString(R.string.moments_refresh_no_due)
    }

    val details = buildList {
        add(context.getString(R.string.moments_refresh_summary, candidateCount, completedCount))
        if (emptyTextCount > 0) {
            add(context.getString(R.string.moments_refresh_empty_text, emptyTextCount))
        }
        if (invalidResponseCount > 0) {
            add(context.getString(R.string.moments_refresh_invalid_response, invalidResponseCount))
        }
        if (modelUnavailableCount > 0) {
            add(context.getString(R.string.moments_refresh_model_unavailable, modelUnavailableCount))
        }
        if (persistenceFailureCount > 0) {
            add(context.getString(R.string.moments_refresh_persistence_failed, persistenceFailureCount))
        }
        if (generationFailureCount > 0) {
            add(context.getString(R.string.moments_refresh_generation_failed, generationFailureCount))
        }
        if (noVisibleCommentCount > 0) {
            add(context.getString(R.string.moments_refresh_no_visible_comment, noVisibleCommentCount))
        }
        if (visionModelRequiredCount > 0) {
            add(context.getString(R.string.moments_refresh_vision_model_required, visionModelRequiredCount))
        }
    }
    return details.joinToString("；")
}

@Composable
private fun MomentsHeader(
    profile: MomentProfile,
    userName: String,
    userAvatar: Avatar,
    processing: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onPost: () -> Unit,
    onCalendar: () -> Unit,
    onCoverSelected: (Uri) -> Unit,
) {
    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let(onCoverSelected)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
    ) {
        if (profile.coverUri.isNotBlank()) {
            AsyncImage(
                model = profile.coverUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { coverPicker.launch("image/*") },
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF6FA0DC))
                    .clickable { coverPicker.launch("image/*") }
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPost) {
                Icon(
                    imageVector = HugeIcons.MessageAdd01,
                    contentDescription = "Post",
                    tint = Color.White,
                )
            }
            DueContentRefreshButton(
                processing = processing,
                contentDescription = "Refresh",
                color = Color.White,
                onRefresh = onRefresh,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCalendar) {
                HollowCalendarIcon(
                    color = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(y = 28.dp)
                .padding(end = 24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = userName,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(bottom = 36.dp)
                    .weight(1f, fill = false)
            )
            UIAvatar(
                name = userName,
                value = userAvatar,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun MomentDateFilterBar(
    label: String,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "正在查看 $label",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) {
            Text("全部")
        }
    }
}

@Composable
private fun EmptyMomentsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "还没有朋友圈",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "发一条，或者等 AI 主动分享点什么。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MomentItem(
    entry: MomentEntry,
    assistant: Assistant,
    assistantName: String,
    userName: String,
    userAvatar: Avatar,
    onLike: () -> Unit,
    onComment: (String) -> Unit,
) {
    val moment = entry.moment
    val isUser = moment.author == MomentAuthor.USER
    val authorName = if (isUser) userName else assistantName
    val authorAvatar = if (isUser) userAvatar else assistant.avatar
    var commentDialogVisible by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UIAvatar(
            name = authorName,
            value = authorAvatar,
            modifier = Modifier.size(48.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = authorName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (moment.content.isNotBlank()) {
                Text(
                    text = moment.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            MomentImages(moment.imageUris)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = relativeTime(moment.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                MomentIconAction(
                    selected = moment.userLiked,
                    contentDescription = "Like",
                    onClick = onLike,
                ) { color, modifier ->
                    HollowHeartIcon(color = color, modifier = modifier)
                }
                MomentIconAction(
                    contentDescription = "Comment",
                    onClick = { commentDialogVisible = true },
                ) { color, modifier ->
                    HollowCommentIcon(color = color, modifier = modifier)
                }
            }
            MomentReactions(
                moment = moment,
                comments = entry.comments,
                assistantName = assistantName,
                userName = userName,
            )
        }
    }

    if (commentDialogVisible) {
        MomentCommentDialog(
            onDismiss = { commentDialogVisible = false },
            onSubmit = {
                onComment(it)
                commentDialogVisible = false
            }
        )
    }
}

@Composable
private fun MomentImages(imageUris: List<String>) {
    if (imageUris.isEmpty()) return
    val visibleUris = imageUris.take(MomentRepository.MAX_IMAGES)
    var showImagePreview by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    if (visibleUris.size == 1) {
        AsyncImage(
            model = visibleUris.first(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .heightIn(min = 140.dp, max = 260.dp)
                .clip(shape)
                .clickable { showImagePreview = true },
            contentScale = ContentScale.Crop,
        )
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 3,
            modifier = Modifier.fillMaxWidth()
        ) {
            visibleUris.forEach { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(shape)
                        .clickable { showImagePreview = true },
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
    if (showImagePreview) {
        ImagePreviewDialog(images = visibleUris) {
            showImagePreview = false
        }
    }
}

@Composable
private fun MomentIconAction(
    selected: Boolean = false,
    contentDescription: String,
    onClick: () -> Unit,
    icon: @Composable (Color, Modifier) -> Unit,
) {
    val color = if (selected) {
        if (isSystemInDarkTheme()) Color(0xFF8FA6C4) else Color(0xFF5E7290)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        icon(color, Modifier.size(22.dp))
    }
}

@Composable
private fun HollowHeartIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.84f)
            cubicTo(w * 0.16f, h * 0.62f, w * 0.02f, h * 0.36f, w * 0.20f, h * 0.18f)
            cubicTo(w * 0.34f, h * 0.04f, w * 0.49f, h * 0.14f, w * 0.50f, h * 0.32f)
            cubicTo(w * 0.51f, h * 0.14f, w * 0.66f, h * 0.04f, w * 0.80f, h * 0.18f)
            cubicTo(w * 0.98f, h * 0.36f, w * 0.84f, h * 0.62f, w * 0.50f, h * 0.84f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = w * 0.08f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun HollowCommentIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.18f)
            lineTo(w * 0.78f, h * 0.18f)
            quadraticTo(w * 0.90f, h * 0.18f, w * 0.90f, h * 0.30f)
            lineTo(w * 0.90f, h * 0.58f)
            quadraticTo(w * 0.90f, h * 0.70f, w * 0.78f, h * 0.70f)
            lineTo(w * 0.54f, h * 0.70f)
            lineTo(w * 0.32f, h * 0.86f)
            lineTo(w * 0.38f, h * 0.70f)
            lineTo(w * 0.22f, h * 0.70f)
            quadraticTo(w * 0.10f, h * 0.70f, w * 0.10f, h * 0.58f)
            lineTo(w * 0.10f, h * 0.30f)
            quadraticTo(w * 0.10f, h * 0.18f, w * 0.22f, h * 0.18f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = w * 0.08f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun HollowCalendarIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = w * 0.08f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.26f)
            quadraticTo(w * 0.20f, h * 0.14f, w * 0.32f, h * 0.14f)
            lineTo(w * 0.68f, h * 0.14f)
            quadraticTo(w * 0.80f, h * 0.14f, w * 0.80f, h * 0.26f)
            lineTo(w * 0.80f, h * 0.76f)
            quadraticTo(w * 0.80f, h * 0.88f, w * 0.68f, h * 0.88f)
            lineTo(w * 0.32f, h * 0.88f)
            quadraticTo(w * 0.20f, h * 0.88f, w * 0.20f, h * 0.76f)
            close()
        }
        drawPath(path = path, color = color, style = stroke)
        drawLine(color, start = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.36f), end = androidx.compose.ui.geometry.Offset(w * 0.80f, h * 0.36f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        drawLine(color, start = androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.08f), end = androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.22f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        drawLine(color, start = androidx.compose.ui.geometry.Offset(w * 0.64f, h * 0.08f), end = androidx.compose.ui.geometry.Offset(w * 0.64f, h * 0.22f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
    }
}

@Composable
private fun MomentReactions(
    moment: Moment,
    comments: List<MomentComment>,
    assistantName: String,
    userName: String,
) {
    val likedNames = buildList {
        if (moment.aiLiked) add(assistantName)
        if (moment.userLiked) add(userName)
    }
    val commentLines = buildList {
        if (moment.aiReplyContent.isNotBlank()) {
            add("$assistantName：${moment.aiReplyContent}")
        }
        comments.forEach { comment ->
            val name = when (comment.author) {
                MomentAuthor.USER -> userName
                MomentAuthor.ASSISTANT -> assistantName
            }
            add("$name：${comment.content}")
        }
    }
    if (likedNames.isEmpty() && commentLines.isEmpty()) return
    val accentColor = if (isSystemInDarkTheme()) Color(0xFF8FA6C4) else Color(0xFF5E7290)
    val reactionBackground = if (isSystemInDarkTheme()) {
        Color(0xFF2F2F2F)
    } else {
        Color(0xFFF1E4D2)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(reactionBackground)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (likedNames.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HollowHeartIcon(
                    color = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = likedNames.joinToString("，"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (likedNames.isNotEmpty() && commentLines.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        }
        commentLines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MomentComposerDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, List<String>) -> Unit,
    saveImages: (List<Uri>) -> List<String>,
) {
    var text by remember { mutableStateOf("") }
    var imageUris by remember { mutableStateOf(emptyList<String>()) }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            imageUris = (imageUris + saveImages(uris))
                .distinct()
                .take(MomentRepository.MAX_IMAGES)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发朋友圈") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = { Text("这一刻的想法") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
                if (imageUris.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        imageUris.forEach { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = imageUris.size < MomentRepository.MAX_IMAGES,
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("添加图片 ${imageUris.size}/${MomentRepository.MAX_IMAGES}")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank() || imageUris.isNotEmpty(),
                onClick = { onSubmit(text, imageUris) }
            ) {
                Text("发布")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun MomentCommentDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("评论") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                placeholder = { Text("写评论") },
            )
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = { onSubmit(text) }
            ) {
                Text("发送")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun relativeTime(timestamp: Long): String {
    val context = LocalContext.current
    return DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString().ifBlank {
        android.text.format.DateFormat.getTimeFormat(context).format(timestamp)
    }
}

private fun showMomentDatePicker(
    context: Context,
    selectedDayStartMillis: Long?,
    onDateSelected: (Long) -> Unit,
) {
    val initial = Calendar.getInstance().apply {
        timeInMillis = selectedDayStartMillis ?: System.currentTimeMillis()
    }
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val selected = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onDateSelected(selected.timeInMillis)
        },
        initial.get(Calendar.YEAR),
        initial.get(Calendar.MONTH),
        initial.get(Calendar.DAY_OF_MONTH),
    ).show()
}

private fun momentDayLabel(dayStartMillis: Long): String {
    return android.text.format.DateFormat.format("yyyy-MM-dd", dayStartMillis).toString()
}

private fun currentDayStartMillis(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
