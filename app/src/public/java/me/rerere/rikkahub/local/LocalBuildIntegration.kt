package me.rerere.rikkahub.local

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.personal.heartbeat.HeartbeatConfigStore
import me.rerere.rikkahub.personal.heartbeat.HeartbeatForegroundService
import me.rerere.rikkahub.personal.heartbeat.HeartbeatNotifications
import me.rerere.rikkahub.personal.heartbeat.HeartbeatScheduleTool
import me.rerere.rikkahub.personal.heartbeat.HeartbeatScheduler
import me.rerere.rikkahub.personal.heartbeat.HeartbeatSettingsActivity
import me.rerere.rikkahub.personal.heartbeat.HeartbeatUserActivity
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.icons.HeartIcon
import kotlin.uuid.Uuid

/**
 * Public distribution boundary.
 *
 * English mode remains a personal-only integration; the heartbeat is now part of public.
 */
object LocalBuildIntegration {
    fun onApplicationCreated(application: Application) {
        HeartbeatNotifications.createChannel(application)
        HeartbeatScheduler.sync(application)
        val store = HeartbeatConfigStore(application)
        val enabled = store.read().enabled
        store.close()
        if (!enabled) HeartbeatForegroundService.stopPersistent(application)
    }

    fun onUserMessageSent(context: Context, message: UIMessage, assistantId: Uuid?) {
        HeartbeatUserActivity.record(context, message, assistantId?.toString())
    }

    fun onAssistantMessageSent(context: Context, message: UIMessage, assistantId: Uuid?) {
        HeartbeatUserActivity.recordAssistantMessage(context, message, assistantId?.toString())
    }

    fun additionalTools(context: Context, assistantId: Uuid?): List<Tool> = listOf(
        HeartbeatScheduleTool(context, assistantId).tool,
    )

    fun additionalConversationModeTools(context: Context, assistantId: Uuid?, conversationId: Uuid?): List<Tool> = emptyList()

    fun isConversationModeEnabled(context: Context, conversationId: Uuid): Boolean = false

    fun conversationMode(context: Context, conversationId: Uuid): LocalConversationMode? = null

    fun observeConversationMode(context: Context, conversationId: Uuid): Flow<Boolean> = flowOf(false)

    fun observeConversationModeKind(context: Context, conversationId: Uuid): Flow<LocalConversationMode?> = flowOf(null)

    fun buildConversationModeSelectionPrompt(selectedText: String): String? = null

    fun isConversationModeSelectionRequest(messages: List<UIMessage>): Boolean = false

    fun isConversationModeSelectionPrompt(message: UIMessage): Boolean = false

    fun isConversationModeSelectionMessage(message: UIMessage): Boolean = false

    fun isConversationModeSelectionResult(tool: UIMessagePart.Tool): Boolean = false

    fun conversationModeGenerationConfig(
        context: Context,
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
    ): LocalConversationModeGenerationConfig? = null

    suspend fun setConversationMode(
        context: Context,
        conversationId: Uuid,
        enabled: Boolean,
        settings: Settings,
        assistant: Assistant,
        messages: List<UIMessage>,
        startNodeId: Uuid?,
        mode: LocalConversationMode = LocalConversationMode.LISTENING,
    ) = Unit

    suspend fun setConversationModeKind(
        context: Context,
        conversationId: Uuid,
        mode: LocalConversationMode,
    ) = Unit

    fun getConversationModeStartNodeId(context: Context, conversationId: Uuid): Uuid? = null

    suspend fun buildConversationModePrompt(
        context: Context,
        assistantId: Uuid,
        conversationId: Uuid,
    ): String? = null

    fun buildConversationModeCompressionPrompt(context: Context): String? = null

    suspend fun extractConversationModeLearning(
        context: Context,
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
        messages: List<UIMessage>,
    ) = Unit

    fun isLocalToolGroupStep(tool: UIMessagePart.Tool): Boolean = false

    fun isLocalLearningStageTool(tool: UIMessagePart.Tool): Boolean = false

    fun isLocalCorrectionToolStep(tool: UIMessagePart.Tool): Boolean = false

    fun isHiddenLocalToolStep(tool: UIMessagePart.Tool): Boolean = false

    fun isMessageRenderedOutsideChat(message: UIMessage): Boolean = false

    @Composable
    fun renderLocalModelSettings(context: Context, settings: Settings) = Unit

    fun splitLocalAssistantText(parts: List<UIMessagePart>, text: String): List<String> = listOf(text)

    fun sanitizeLocalToolStageMessage(message: UIMessage): UIMessage = message

    fun localToolContinuationPrompt(tools: List<UIMessagePart.Tool>): String? = null

    @Composable
    fun renderConversationModeSelectionResult(
        tool: UIMessagePart.Tool?,
        onDismiss: () -> Unit,
    ) = Unit
}

fun CardGroupScope.addLocalSettingsExtension(context: Context) {
    item(
        onClick = { context.startActivity(Intent(context, HeartbeatSettingsActivity::class.java)) },
        leadingContent = { Icon(HeartIcon, null) },
        supportingContent = { Text(stringResource(R.string.heartbeat_settings_description)) },
        headlineContent = { Text(stringResource(R.string.heartbeat_settings_title)) },
    )
}

@Composable
fun ChainOfThoughtScope.renderLocalToolStep(tool: UIMessagePart.Tool, loading: Boolean): Boolean = false

@Composable
fun LocalConversationModeButton(conversationId: Uuid, onToggle: () -> Unit) = Unit

@Composable
fun LocalConversationModeSelector(
    mode: LocalConversationMode,
    onSelect: (LocalConversationMode) -> Unit,
) = Unit

@Composable
fun LocalEnglishReviewButton() = Unit

@Composable
fun LocalConversationModeLabel(conversationId: Uuid) = Unit
