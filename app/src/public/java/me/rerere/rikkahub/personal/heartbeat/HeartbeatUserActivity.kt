package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.Logging

/** Records user activity before the next heartbeat is calculated. */
object HeartbeatUserActivity {
    fun record(context: Context, message: UIMessage, assistantId: String?) {
        if (message.role != MessageRole.USER) return

        val messageAt = message.createdAt.toMessageAtMillis()
        val text = message.toText().trim()
        val store = HeartbeatConfigStore(context, assistantId)
        val wasGoodNightActive = store.isGoodNightActive()
        store.setLastUserMessageAt(messageAt)
        store.recordDesireState(store.readDesireState().afterUserMessage(System.currentTimeMillis()))
        var shouldRearmForModeChange = false
        when {
            text.contains(GOOD_NIGHT_MARKER) -> {
                store.setGoodNightActive(true)
                store.setGoodNightNoUsageRuns(0)
                shouldRearmForModeChange = true
                Logging.log("Heartbeat", "goodnight=activated:user-message")
            }

            wasGoodNightActive -> {
                store.setGoodNightActive(false)
                store.setGoodNightNoUsageRuns(0)
                shouldRearmForModeChange = true
                Logging.log("Heartbeat", "goodnight=deactivated:user-message")
            }
        }
        val config = store.read()
        store.close()

        if (config.enabled && shouldRearmForModeChange) {
            // Mode changes need an immediate scheduling update; ordinary user messages do not
            // move the heartbeat clock, which is anchored to the assistant's last message.
            HeartbeatScheduler.scheduleNext(context, config)
        }
    }

    fun recordAssistantMessage(
        context: Context,
        message: UIMessage,
        assistantId: String?,
        reschedule: Boolean = true,
    ) {
        if (message.role != MessageRole.ASSISTANT || message.toText().isBlank()) return

        val store = HeartbeatConfigStore(context, assistantId)
        store.setLastAssistantMessageAt(message.createdAt.toMessageAtMillis())
        val config = store.read()
        store.close()

        if (reschedule && config.enabled) {
            HeartbeatScheduler.scheduleNext(context, config)
        }
    }

    private fun kotlinx.datetime.LocalDateTime.toMessageAtMillis(): Long =
        toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private const val GOOD_NIGHT_MARKER = "晚安"
}
