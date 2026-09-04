package me.rerere.rikkahub.personal.heartbeat

import android.content.Context
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.VoiceCallSessionRegistry
import kotlin.uuid.Uuid

enum class HeartbeatDeliveryBlock {
    VOICE_CALL_ACTIVE,
    CONVERSATION_BUSY,
    USER_RETURNED,
    HEARTBEAT_DISABLED,
    TARGET_CHANGED,
}

/** Rechecks all user-facing delivery hazards immediately before a heartbeat is written. */
class HeartbeatDeliveryGuard(
    private val context: Context,
    private val chatService: ChatService,
) {
    suspend fun beforeGeneration(
        conversationId: Uuid,
        expectedAssistantId: String,
    ): HeartbeatDeliveryBlock? {
        if (VoiceCallSessionRegistry.isActive()) return HeartbeatDeliveryBlock.VOICE_CALL_ACTIVE
        if (isConversationGenerating(conversationId)) {
            return HeartbeatDeliveryBlock.CONVERSATION_BUSY
        }
        currentConfigurationBlock(expectedAssistantId)?.let { return it }
        return null
    }

    suspend fun beforeDelivery(
        conversationId: Uuid,
        runStartedAtMillis: Long,
        expectedAssistantId: String,
    ): HeartbeatDeliveryBlock? {
        beforeGeneration(conversationId, expectedAssistantId)?.let { return it }
        val store = HeartbeatConfigStore(context, expectedAssistantId)
        val lastUserMessageAt = store.lastUserMessageAt()
        store.close()
        return HeartbeatDeliveryBlock.USER_RETURNED
            .takeIf { lastUserMessageAt > runStartedAtMillis }
    }

    private fun currentConfigurationBlock(expectedAssistantId: String): HeartbeatDeliveryBlock? {
        val store = HeartbeatConfigStore(context, expectedAssistantId)
        val config = store.read()
        store.close()
        if (!config.enabled) return HeartbeatDeliveryBlock.HEARTBEAT_DISABLED
        if (config.assistantId != null && config.assistantId != expectedAssistantId) {
            return HeartbeatDeliveryBlock.TARGET_CHANGED
        }
        return null
    }

    private suspend fun isConversationGenerating(conversationId: Uuid): Boolean =
        chatService.getConversationJobs().first()[conversationId]?.isActive == true
}
