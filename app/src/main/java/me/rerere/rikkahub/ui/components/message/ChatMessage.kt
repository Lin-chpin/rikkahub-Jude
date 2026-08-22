package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.voice.voiceCallRecord
import me.rerere.rikkahub.data.voice.chatVoiceReply
import me.rerere.rikkahub.data.voice.chatVoiceReplyDraft
import me.rerere.rikkahub.data.voice.hasChatVoiceReplyTool
import me.rerere.rikkahub.data.voice.hasChatVoiceReplyToolError
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.memoryScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.voice.withoutVoiceCallAudioTagsForChatDisplay
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.LocalElevenLabsAudioTagAnnotations
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.rememberChatTtsPlayback
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.toChatTtsText
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.rikkahub.utils.urlDecode
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onTranslateChatVoiceSegment: ((UIMessage, Int, String, Locale) -> Unit)? = null,
    onClearChatVoiceSegmentTranslation: ((UIMessage, Int) -> Unit)? = null,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenVoiceCallRecord: ((String) -> Unit)? = null,
    onUpdateTtsMessage: (messageId: kotlin.uuid.Uuid, transform: (UIMessage) -> UIMessage) -> Unit = { _, _ -> },
) {
    val message = node.messages[node.selectIndex]
    val chatVoiceReply = message.chatVoiceReply()
    val chatVoiceReplyDraft = message.chatVoiceReplyDraft()
    val pendingChatVoiceReply = chatVoiceReply == null &&
        !message.hasChatVoiceReplyToolError() &&
        ((chatVoiceReplyDraft != null && (message.hasChatVoiceReplyTool() || loading)) ||
            (message.hasChatVoiceReplyTool() && loading))
    val voiceCallRecord = message.voiceCallRecord()
    if (voiceCallRecord != null) {
        if (voiceCallRecord.cardAnchor) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = modifier.combinedClickable(
                    onClick = { onOpenVoiceCallRecord?.invoke(voiceCallRecord.callId) },
                    onLongClick = onDelete,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(HugeIcons.Voice, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("通话记录", style = MaterialTheme.typography.labelLarge)
                    Text(formatVoiceCallDuration(voiceCallRecord.durationSeconds), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    val appSettings = LocalSettings.current
    val settings = appSettings.displaySetting
    val chatTts = rememberChatTtsPlayback()
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        ProvideTextStyle(textStyle) {
            val onTtsSpeak: ((String) -> Unit)? = if (message.role == MessageRole.ASSISTANT) {
                { text ->
                    chatTts.speak(
                        message = message,
                        text = text,
                        onUpdateMessage = onUpdateTtsMessage,
                    )
                }
            } else {
                null
            }
            when {
                chatVoiceReply != null && message.role == MessageRole.ASSISTANT -> {
                    ChatVoiceReplyMessageContent(
                        message = message,
                        reply = chatVoiceReply,
                        assistant = assistant,
                        model = model,
                        loading = loading,
                        onTtsSpeak = onTtsSpeak,
                        onTranslateSegment = onTranslateChatVoiceSegment,
                        onClearSegmentTranslation = onClearChatVoiceSegmentTranslation,
                        onToolApproval = onToolApproval,
                        onToolAnswer = onToolAnswer,
                    )
                }

                pendingChatVoiceReply && message.role == MessageRole.ASSISTANT -> {
                    ChatVoiceReplyPendingContent(
                        textSegments = chatVoiceReplyDraft?.segments.orEmpty().filter {
                            it.type == me.rerere.ai.ui.ChatVoiceReplySegmentType.TEXT
                        }.takeIf { loading }.orEmpty(),
                        assistant = assistant,
                        loading = loading,
                        onTtsSpeak = onTtsSpeak,
                    )
                }

                else -> {
                    MessagePartsBlock(
                        assistant = assistant,
                        role = message.role,
                        parts = if (message.role == MessageRole.ASSISTANT) {
                            message.parts.withoutVoiceCallAudioTagsForChatDisplay()
                        } else {
                            message.parts
                        },
                        annotations = message.annotations,
                        loading = loading,
                        showElevenLabsAudioTagAnnotations = false,
                        model = model,
                        onToolApproval = onToolApproval,
                        onToolAnswer = onToolAnswer,
                        onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
                        onTtsSpeak = onTtsSpeak,
                    )
                }
            }

            message.translation?.takeIf { chatVoiceReply == null }?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        val showActions = if (pendingChatVoiceReply) {
            false
        } else if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTtsSpeak = { text ->
                        chatTts.speak(
                            message = message,
                            text = text,
                            onUpdateMessage = onUpdateTtsMessage,
                        )
                    },
                    onTranslate = onTranslate.takeIf { chatVoiceReply == null },
                    onClearTranslation = onClearTranslation
                )
            }
        }

        if (!pendingChatVoiceReply) {
            ProvideTextStyle(textStyle) {
                ChatMessageNerdLine(message = message)
            }
        }
    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    navController.navigate(Screen.WebView(content = htmlContent.base64Encode()))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}
@OptIn(FlowPreview::class)
@Composable
internal fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    showElevenLabsAudioTagAnnotations: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    onTtsSpeak: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        memoryScope = assistant?.memoryScope,
                                        loading = loading && !step.tool.isExecuted,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        if (role == MessageRole.USER) {
                            val userContent = part.text.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.USER,
                                visual = true,
                            )
                            if (assistant?.momentsChatStyle == true) {
                                UserTextParagraphs(
                                    content = userContent,
                                    onClickCitation = handleClickCitation,
                                    selectionEnabled = !loading,
                                    onClick = onUserMessageClick,
                                )
                            } else {
                                val userTextContent = @Composable {
                                    Surface(
                                        modifier = Modifier.animateContentSize(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        onClick = { onUserMessageClick?.invoke() },
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            MarkdownBlock(
                                                content = userContent,
                                                onClickCitation = handleClickCitation
                                            )
                                        }
                                    }
                                }
                                if (loading) {
                                    userTextContent()
                                } else {
                                    SelectionContainer {
                                        userTextContent()
                                    }
                                }
                            }
                        } else {
                            val assistantContent = part.text.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.ASSISTANT,
                                visual = true,
                            )
                            val momentsChatStyle = assistant?.momentsChatStyle == true
                            if (momentsChatStyle) {
                                AssistantTextContent(
                                    content = assistantContent,
                                    onClickCitation = handleClickCitation,
                                    selectionEnabled = !loading,
                                    showElevenLabsAudioTagAnnotations = showElevenLabsAudioTagAnnotations,
                                    showParagraphTtsButtons = settings.displaySetting.showParagraphTtsButtons,
                                    paragraphBubbleMode = true,
                                    modifier = Modifier.animateContentSize(),
                                    onTtsSpeak = onTtsSpeak,
                                )
                            } else if (settings.displaySetting.showAssistantBubble) {
                                Surface(
                                    modifier = Modifier.animateContentSize(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = assistantMessageBubbleColor(),
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        AssistantTextContent(
                                            content = assistantContent,
                                            onClickCitation = handleClickCitation,
                                            selectionEnabled = !loading,
                                            showElevenLabsAudioTagAnnotations = showElevenLabsAudioTagAnnotations,
                                            showParagraphTtsButtons = settings.displaySetting.showParagraphTtsButtons,
                                            paragraphBubbleMode = false,
                                            onTtsSpeak = onTtsSpeak,
                                        )
                                    }
                                }
                            } else {
                                AssistantTextContent(
                                    content = assistantContent,
                                    onClickCitation = handleClickCitation,
                                    selectionEnabled = !loading,
                                    showElevenLabsAudioTagAnnotations = showElevenLabsAudioTagAnnotations,
                                    showParagraphTtsButtons = settings.displaySetting.showParagraphTtsButtons,
                                    paragraphBubbleMode = false,
                                    modifier = Modifier
                                        .animateContentSize(),
                                    onTtsSpeak = onTtsSpeak,
                                )
                            }
                        }
                    }

                    is UIMessagePart.Video -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Video01, null)
                            }
                        }
                    }

                    is UIMessagePart.Audio -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MusicNote03,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .height(72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.docx),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        "application/pdf" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.pdf),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        else -> {
                                            Icon(
                                                imageVector = HugeIcons.File02,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = part.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    val citationAnnotations = annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>()
    if (citationAnnotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        citationAnnotations.fastForEachIndexed { index, annotation ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                Text(
                                    text = buildAnnotatedString {
                                        append("${index + 1}. ")
                                        withLink(LinkAnnotation.Url(annotation.url)) {
                                            append(annotation.title.urlDecode())
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, citationAnnotations.size))
            }
        }
    }
}
@Composable
private fun UserTextParagraphs(
    content: String,
    onClickCitation: (String) -> Unit,
    selectionEnabled: Boolean,
    onClick: (() -> Unit)?,
) {
    val segments = remember(content) {
        content.splitAssistantTextSegments()
    }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        segments.fastForEach { segment ->
            when (segment) {
                AssistantTextSegment.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                    )
                }

                is AssistantTextSegment.Paragraph -> {
                    Surface(
                        modifier = Modifier.animateContentSize(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        onClick = { onClick?.invoke() },
                    ) {
                        val paragraphContent: @Composable () -> Unit = {
                            Column(modifier = Modifier.padding(8.dp)) {
                                MarkdownBlock(
                                    content = segment.text,
                                    onClickCitation = onClickCitation,
                                )
                            }
                        }
                        if (selectionEnabled) {
                            SelectionContainer {
                                paragraphContent()
                            }
                        } else {
                            paragraphContent()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMarkdownBlock(
    content: String,
    onClickCitation: (String) -> Unit,
    showElevenLabsAudioTagAnnotations: Boolean,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalElevenLabsAudioTagAnnotations provides showElevenLabsAudioTagAnnotations,
    ) {
        MarkdownBlock(
            content = content,
            onClickCitation = onClickCitation,
            modifier = modifier,
        )
    }
}

@Composable
internal fun AssistantTextContent(
    content: String,
    onClickCitation: (String) -> Unit,
    selectionEnabled: Boolean,
    showElevenLabsAudioTagAnnotations: Boolean,
    showParagraphTtsButtons: Boolean,
    paragraphBubbleMode: Boolean,
    modifier: Modifier = Modifier,
    onTtsSpeak: ((String) -> Unit)? = null,
) {
    if (paragraphBubbleMode) {
        AssistantTextParagraphs(
            content = content,
            onClickCitation = onClickCitation,
            modifier = modifier,
            selectionEnabled = selectionEnabled,
            showElevenLabsAudioTagAnnotations = showElevenLabsAudioTagAnnotations,
            showParagraphTtsButtons = showParagraphTtsButtons,
            paragraphBubbleMode = true,
            onTtsSpeak = onTtsSpeak,
        )
    } else if (showParagraphTtsButtons) {
        AssistantTextParagraphs(
            content = content,
            onClickCitation = onClickCitation,
            modifier = modifier,
            selectionEnabled = selectionEnabled,
            showElevenLabsAudioTagAnnotations = showElevenLabsAudioTagAnnotations,
            showParagraphTtsButtons = true,
            paragraphBubbleMode = false,
            onTtsSpeak = onTtsSpeak,
        )
    } else if (selectionEnabled) {
        SelectionContainer(modifier = modifier) {
            AssistantMarkdownBlock(
                content = content,
                onClickCitation = onClickCitation,
                showElevenLabsAudioTagAnnotations = showElevenLabsAudioTagAnnotations,
            )
        }
    } else {
        AssistantMarkdownBlock(
            content = content,
            onClickCitation = onClickCitation,
            modifier = modifier,
            showElevenLabsAudioTagAnnotations = showElevenLabsAudioTagAnnotations,
        )
    }
}

@Composable
private fun AssistantTextParagraphs(
    content: String,
    onClickCitation: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectionEnabled: Boolean = true,
    showElevenLabsAudioTagAnnotations: Boolean,
    showParagraphTtsButtons: Boolean,
    paragraphBubbleMode: Boolean,
    onTtsSpeak: ((String) -> Unit)? = null,
) {
    val tts = LocalTTSState.current
    val isAvailable by tts.isAvailable.collectAsState()
    val displaySetting = LocalSettings.current.displaySetting
    val paragraphBubbleColor = assistantMessageBubbleColor()
    val segments = remember(content) {
        content.splitAssistantTextSegments()
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.fastForEach { segment ->
            when (segment) {
                AssistantTextSegment.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                    )
                }

                is AssistantTextSegment.Paragraph -> {
                    val textToSpeak = remember(
                        segment.text,
                        displaySetting.ttsOnlyReadQuoted,
                        displaySetting.ttsEnglishOnly,
                    ) {
                        segment.text.toChatTtsText(
                            ttsOnlyReadQuoted = displaySetting.ttsOnlyReadQuoted,
                            ttsEnglishOnly = displaySetting.ttsEnglishOnly,
                        )
                    }
                    val canSpeak = isAvailable && textToSpeak.isNotBlank()
                    val paragraphContent: @Composable (Modifier) -> Unit = { rowModifier ->
                        Row(
                            modifier = rowModifier,
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = if (showParagraphTtsButtons) {
                                    Modifier.weight(1f)
                                } else {
                                    Modifier
                                }
                            ) {
                                if (selectionEnabled) {
                                    SelectionContainer {
                                        AssistantMarkdownBlock(
                                            content = segment.text,
                                            onClickCitation = onClickCitation,
                                            showElevenLabsAudioTagAnnotations = showElevenLabsAudioTagAnnotations,
                                        )
                                    }
                                } else {
                                    AssistantMarkdownBlock(
                                        content = segment.text,
                                        onClickCitation = onClickCitation,
                                        showElevenLabsAudioTagAnnotations = showElevenLabsAudioTagAnnotations,
                                    )
                                }
                            }
                            if (showParagraphTtsButtons) {
                                IconButton(
                                    enabled = canSpeak,
                                    onClick = {
                                        onTtsSpeak?.invoke(textToSpeak)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.VolumeHigh,
                                        contentDescription = stringResource(R.string.tts),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = if (canSpeak) 0.65f else 0.38f
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (paragraphBubbleMode) {
                        Surface(
                            modifier = Modifier
                                .animateContentSize(),
                            shape = RoundedCornerShape(8.dp),
                            color = paragraphBubbleColor,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            paragraphContent(
                                Modifier
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    } else {
                        paragraphContent(
                            Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private sealed interface AssistantTextSegment {
    data class Paragraph(val text: String) : AssistantTextSegment
    data object Divider : AssistantTextSegment
}

private val markdownDividerLineRegex = Regex("^[\\s\\u200B\\u200C\\u200D]*([-*_])(?:[\\s\\u200B\\u200C\\u200D]*\\1){2,}[\\s\\u200B\\u200C\\u200D]*$")

private fun String.splitAssistantTextSegments(): List<AssistantTextSegment> {
    val segments = mutableListOf<AssistantTextSegment>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotBlank()) {
            segments += AssistantTextSegment.Paragraph(text)
        }
        paragraph.clear()
    }

    trim().lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            markdownDividerLineRegex.matches(line) -> {
                flushParagraph()
                segments += AssistantTextSegment.Divider
            }

            line.isBlank() -> {
                flushParagraph()
            }

            else -> {
                if (paragraph.isNotEmpty()) {
                    paragraph.appendLine()
                }
                paragraph.append(rawLine)
            }
        }
    }
    flushParagraph()

    return segments.ifEmpty { listOf(AssistantTextSegment.Paragraph(this)) }
}

private fun formatVoiceCallDuration(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    } else {
        "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
