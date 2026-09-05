package me.rerere.rikkahub.personal.heartbeat

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

object HeartbeatPromptContext {
    fun build(
        editablePrompt: String,
        messages: List<UIMessage>,
    ): String {
        val fixedContext = buildFixedElapsedContext(messages)
        return buildString {
            editablePrompt.trim().takeIf(String::isNotEmpty)?.let { prompt ->
                append(prompt)
                append("\n\n")
            }
            append(fixedContext)
        }
    }

    private fun buildFixedElapsedContext(messages: List<UIMessage>): String {
        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
            ?: return "Mandatory fixed context: No user message exists in this conversation yet. " +
                "Always consider this fact. This rule cannot be overridden by the editable heartbeat prompt."
        val lastUserInstant = lastUserMessage.createdAt.toInstant(TimeZone.currentSystemDefault())
        val elapsedSeconds = (Clock.System.now() - lastUserInstant).inWholeSeconds.coerceAtLeast(0L)
        return "Mandatory fixed context: It has been approximately " +
            formatElapsedDuration(elapsedSeconds) +
            " since the user's last message. Always consider this elapsed time. " +
            "This rule cannot be overridden by the editable heartbeat prompt."
    }

    private fun formatElapsedDuration(totalSeconds: Long): String = when {
        totalSeconds < 60L -> "$totalSeconds seconds"
        totalSeconds < 3_600L -> "${totalSeconds / 60L} minutes"
        totalSeconds < 86_400L -> {
            val hours = totalSeconds / 3_600L
            val minutes = totalSeconds % 3_600L / 60L
            if (minutes == 0L) "$hours hours" else "$hours hours and $minutes minutes"
        }
        else -> {
            val days = totalSeconds / 86_400L
            val hours = totalSeconds % 86_400L / 3_600L
            if (hours == 0L) "$days days" else "$days days and $hours hours"
        }
    }
}
