package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoView
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.AnonymousQuestionAuthor
import me.rerere.rikkahub.data.repository.AnonymousQuestionEntry
import me.rerere.rikkahub.data.repository.AnonymousQuestionReply
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import kotlin.uuid.Uuid

@Composable
fun AnonymousQuestionBoxOverlay(
    visible: Boolean,
    scopeId: Uuid,
    assistant: Assistant,
    conversation: Conversation,
    settings: Settings,
    conversationSystemPrompt: String?,
    vm: AnonymousQuestionBoxVM,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val isDark = isSystemInDarkTheme()
    val questionBoxBackground = if (isDark) {
        MaterialTheme.colorScheme.background
    } else {
        androidx.compose.ui.graphics.Color(0xFFFFF1F5)
    }
    val questions by remember(scopeId) { vm.observeQuestions(scopeId) }.collectAsStateWithLifecycle(emptyList())
    val processing by vm.processing.collectAsStateWithLifecycle()
    var composerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(scopeId, conversation.compressedSummary) {
        vm.processDue(scopeId, assistant, conversation, conversationSystemPrompt)
        vm.markViewed(scopeId)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = questionBoxBackground) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, contentDescription = null) }
                    Text(stringResource(R.string.anonymous_question_box), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    DueContentRefreshButton(
                        processing = processing,
                        contentDescription = stringResource(R.string.anonymous_question_box_refresh),
                        color = MaterialTheme.colorScheme.onSurface,
                        onRefresh = {
                            vm.processDue(scopeId, assistant, conversation, conversationSystemPrompt)
                            vm.markViewed(scopeId)
                        },
                    )
                    IconButton(onClick = { composerVisible = true }) { Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.anonymous_question_box_publish)) }
                }
                if (questions.isEmpty()) {
                    Text(stringResource(R.string.anonymous_question_box_empty), modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 80.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(12.dp)) {
                        items(questions, key = { it.question.id.toString() }) { entry -> AnonymousQuestionCard(entry, assistant, settings, vm) }
                    }
                }
            }
        }
    }
    if (composerVisible) AnonymousQuestionComposer(
        onDismiss = { composerVisible = false },
        onSubmit = { content -> vm.postUserQuestion(scopeId, content); composerVisible = false },
    )
}

@Composable
private fun AnonymousQuestionCard(entry: AnonymousQuestionEntry, assistant: Assistant, settings: Settings, vm: AnonymousQuestionBoxVM) {
    var expanded by remember(entry.question.id) { mutableStateOf(false) }
    var answerFocused by remember(entry.question.id) { mutableStateOf(false) }
    val answerBringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(answerFocused) {
        if (answerFocused) {
            delay(250)
            answerBringIntoViewRequester.bringIntoView()
        }
    }
    var answer by remember(entry.question.id) { mutableStateOf("") }
    val isAssistantQuestion = entry.question.author == AnonymousQuestionAuthor.ASSISTANT
    val avatar = if (isAssistantQuestion) assistant.avatar else settings.displaySetting.userAvatar
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { expanded = !expanded },
            color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.White,
            contentColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color(0xFF241A1D),
            tonalElevation = 0.dp,
        ) {
            Row(verticalAlignment = Alignment.Top) {
                UIAvatar(
                    name = "",
                    value = avatar,
                    modifier = Modifier
                        .padding(start = 14.dp, top = 14.dp, bottom = 14.dp)
                        .size(30.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = entry.question.content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 14.dp, end = 14.dp, bottom = 14.dp),
                )
            }
        }
        if (expanded) {
            entry.replies.forEach { reply ->
                AnonymousQuestionReplyCard(
                    reply = reply,
                    assistant = assistant,
                    settings = settings,
                )
            }
            if (isAssistantQuestion && entry.replies.none { it.author == AnonymousQuestionAuthor.USER }) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(answerBringIntoViewRequester)
                                .onFocusChanged { answerFocused = it.isFocused },
                            placeholder = { Text(stringResource(R.string.anonymous_question_box_answer)) },
                        )
                        Button(
                            onClick = {
                                vm.addUserAnswer(entry.question, answer)
                                answer = ""
                            },
                            enabled = answer.isNotBlank(),
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.anonymous_question_box_submit))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnonymousQuestionReplyCard(
    reply: AnonymousQuestionReply,
    assistant: Assistant,
    settings: Settings,
) {
    val isAssistantReply = reply.author == AnonymousQuestionAuthor.ASSISTANT
    val avatar = if (isAssistantReply) assistant.avatar else settings.displaySetting.userAvatar
    val cardColor = if (isAssistantReply) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = if (isAssistantReply) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp),
        shape = RoundedCornerShape(16.dp),
        color = cardColor,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            UIAvatar(name = "", value = avatar, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(10.dp))
            Text(
                text = reply.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = textColor,
            )
        }
    }
}

@Composable
private fun AnonymousQuestionComposer(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.anonymous_question_box_publish)) },
        text = { OutlinedTextField(value = content, onValueChange = { content = it }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
        confirmButton = { TextButton(onClick = { onSubmit(content) }, enabled = content.isNotBlank()) { Text(stringResource(R.string.anonymous_question_box_submit)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
