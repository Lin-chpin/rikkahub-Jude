package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.voice.isStandaloneVoiceCallRecord

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    val compressedSummary: String? = null,
    val compressedMessageNodeIds: Set<Uuid> = emptySet(),
    val autoCompressConfig: AutoCompressConfig? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() {
            val messages = messageNodes.flatMap { it.messages }
            val partFiles = messages
                .flatMap { it.parts }
                .collectAllParts()
                .mapNotNull { it.fileUri() }
            val ttsFiles = messages
                .flatMap { it.annotations }
                .filterIsInstance<UIMessageAnnotation.TtsAudio>()
                .map { it.audioUri.toUri() }
            val chatVoiceFiles = messages
                .flatMap { it.annotations }
                .filterIsInstance<UIMessageAnnotation.ChatVoiceReply>()
                .flatMap { reply -> reply.segments }
                .flatMap { segment -> segment.audioSegments }
                .map { it.audioUri.toUri() }
            return (partFiles + ttsFiles + chatVoiceFiles).distinct()
        }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes
                .filterNot { it.currentMessage.isStandaloneVoiceCallRecord() }
                .map { node -> node.currentMessage }
        }

    val visibleMessageNodes: List<MessageNode>
        get() = messageNodes.filterNot { it.id in activeCompressedMessageNodeIds }

    val hasCompressedMessages: Boolean
        get() = activeCompressedMessageNodeIds.isNotEmpty()

    val activeCompressedMessageNodeIds: Set<Uuid>
        get() {
            if (messageNodes.isEmpty() || compressedMessageNodeIds.isEmpty()) {
                return emptySet()
            }
            val existingNodeIds = messageNodes.mapTo(mutableSetOf()) { it.id }
            val activeIds = compressedMessageNodeIds.filterTo(mutableSetOf()) { it in existingNodeIds }
            return if (activeIds.size < messageNodes.size) {
                activeIds
            } else {
                activeIds - messageNodes.last().id
            }
        }

    fun normalizeCompressionState(): Conversation {
        val activeIds = activeCompressedMessageNodeIds
        return if (activeIds == compressedMessageNodeIds) {
            this
        } else {
            copy(compressedMessageNodeIds = activeIds)
        }
    }

    /**
     * Applies an asynchronous compression result only while its summary base is still current.
     * New messages may be appended during compression, but another compression or summary edit wins.
     */
    fun withCompressionResultIfBaseUnchanged(
        expectedSummary: String?,
        expectedCompressedNodeIds: Set<Uuid>,
        newSummary: String?,
        nodeIdsToCompress: Set<Uuid>,
        newAutoCompressConfig: AutoCompressConfig?,
    ): Conversation? {
        val normalized = normalizeCompressionState()
        if (
            normalized.compressedSummary != expectedSummary ||
            normalized.activeCompressedMessageNodeIds != expectedCompressedNodeIds
        ) {
            return null
        }

        val existingNodeIds = normalized.messageNodes.mapTo(mutableSetOf()) { it.id }
        val applicableNodeIds = nodeIdsToCompress.filterTo(mutableSetOf()) { it in existingNodeIds }
        if (applicableNodeIds.isEmpty()) return null

        return normalized.copy(
            compressedSummary = newSummary,
            compressedMessageNodeIds = normalized.activeCompressedMessageNodeIds + applicableNodeIds,
            autoCompressConfig = newAutoCompressConfig ?: normalized.autoCompressConfig,
        ).normalizeCompressionState()
    }

    /**
     * Remaps compression metadata when a visible message prefix is copied into a fork.
     * A fork created inside compressed history cannot reuse the rolling summary because
     * that summary may contain messages that occur after the selected fork point.
     */
    fun compressionStateForFork(
        targetNodeId: Uuid,
        copiedNodeIdsBySourceId: Map<Uuid, Uuid>,
    ): ConversationForkCompressionState {
        val activeCompressedNodeIds = activeCompressedMessageNodeIds
        if (compressedSummary.isNullOrBlank() || targetNodeId in activeCompressedNodeIds) {
            return ConversationForkCompressionState()
        }

        val remappedCompressedNodeIds = activeCompressedNodeIds
            .mapNotNullTo(mutableSetOf()) { copiedNodeIdsBySourceId[it] }
        if (remappedCompressedNodeIds.isEmpty()) {
            return ConversationForkCompressionState()
        }

        return ConversationForkCompressionState(
            summary = compressedSummary,
            compressedNodeIds = remappedCompressedNodeIds,
        )
    }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(
        messages: List<UIMessage>,
        messagesAreVisibleInOrder: Boolean = false,
    ): Conversation {
        val newNodes = this.messageNodes.toMutableList()
        val compressedNodeIds = activeCompressedMessageNodeIds
        val targetNodeIndices = newNodes.mapIndexedNotNull { index, node ->
            index.takeIf {
                node.id !in compressedNodeIds &&
                    !node.currentMessage.isStandaloneVoiceCallRecord()
            }
        }

        if (messagesAreVisibleInOrder) {
            messages.forEachIndexed { index, message ->
                val nodeIndex = targetNodeIndices.getOrNull(index)
                val node = if (nodeIndex != null) {
                    newNodes[nodeIndex]
                } else {
                    message.toMessageNode()
                }

                val newMessages = node.messages.toMutableList()
                val existingMessageIndex = newMessages.indexOfFirst { it.id == message.id }
                val newMessageIndex = if (existingMessageIndex >= 0) {
                    newMessages[existingMessageIndex] = message
                    node.selectIndex
                } else {
                    newMessages.add(message)
                    newMessages.lastIndex
                }
                val newNode = node.copy(
                    messages = newMessages,
                    selectIndex = newMessageIndex,
                )
                if (nodeIndex == null) {
                    newNodes.add(newNode)
                } else {
                    newNodes[nodeIndex] = newNode
                }
            }
            return copy(messageNodes = newNodes)
        }

        val existingNodeIndexByMessageId = buildMap {
            newNodes.forEachIndexed { nodeIndex, node ->
                node.messages.forEach { message -> put(message.id, nodeIndex) }
            }
        }

        messages.forEachIndexed { index, message ->
            // Whole-conversation transforms may include compressed messages. Match identity first
            // so those messages stay in their original hidden nodes; generation output can still
            // fall back to the visible positional projection used by the streaming pipeline.
            val nodeIndex = existingNodeIndexByMessageId[message.id]
                ?: targetNodeIndices.getOrNull(index)
            val node = if (nodeIndex != null) {
                newNodes[nodeIndex]
            } else {
                message.toMessageNode()
            }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (nodeIndex == null) {
                newNodes.add(newNode)
            } else {
                newNodes[nodeIndex] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class AutoCompressConfig(
    val enabled: Boolean = false,
    val additionalPrompt: String = "",
    val targetTokens: Int = 2000,
    val keepRecentMessages: Int = 32,
)

data class ConversationForkCompressionState(
    val summary: String? = null,
    val compressedNodeIds: Set<Uuid> = emptySet(),
)

fun Conversation.messagesForGeneration(messageRange: ClosedRange<Int>? = null): List<UIMessage> {
    val sourceNodes = if (messageRange != null) {
        messageNodes.subList(messageRange.start, messageRange.endInclusive + 1)
    } else {
        messageNodes
    }
    val visibleMessages = sourceNodes
        .filterNot { it.id in compressedMessageNodeIds }
        .filterNot { it.currentMessage.isStandaloneVoiceCallRecord() }
        .map { it.currentMessage }
    return visibleMessages.ifEmpty {
        sourceNodes.lastOrNull { !it.currentMessage.isStandaloneVoiceCallRecord() }
            ?.currentMessage?.let(::listOf).orEmpty()
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

fun Conversation.momentScopeId(assistant: Assistant): Uuid {
    return personaScopeId(assistant)
}

fun Conversation.personaScopeId(assistant: Assistant): Uuid {
    return if (usesIndependentMomentScope(assistant)) id else assistantId
}

fun Conversation.usesIndependentMomentScope(assistant: Assistant): Boolean {
    val hasConversationSystemPrompt = assistant.allowConversationSystemPrompt &&
        customSystemPrompt?.isNotBlank() == true
    val hasConversationPromptInjection = assistant.allowConversationPromptInjection &&
        (modeInjectionIds.isNotEmpty() || lorebookIds.isNotEmpty())
    return hasConversationSystemPrompt || hasConversationPromptInjection
}

fun Conversation.momentPersonaName(assistant: Assistant): String {
    val fallback = assistant.name.ifBlank { "AI" }
    val prompt = customSystemPrompt
        ?.takeIf { assistant.allowConversationSystemPrompt && it.isNotBlank() }
        ?: return fallback
    return prompt.extractPersonaNameFromPrompt() ?: fallback
}

private fun String.extractPersonaNameFromPrompt(): String? {
    val patterns = listOf(
        Regex("""(?im)(?:人设名字|角色名字|角色名|姓名|名字|名称|昵称)\s*(?:是|为|叫|叫做|名为|[:：])\s*([^\n，。,；;、]{1,40})"""),
        Regex("""(?im)(?:你叫|你名叫|你名为|你叫做)\s*([^\n，。,；;、]{1,40})"""),
    )
    return patterns
        .firstNotNullOfOrNull { pattern ->
            pattern.find(this)?.groupValues?.getOrNull(1)?.cleanPersonaName()
        }
}

private fun String.cleanPersonaName(): String? {
    val stopChars = setOf(
        '，', ',', '。', '.', '；', ';', '：', ':', '\n', '\r', '\t', ' ', '　',
        '/', '|', '）', ')', '】', ']', '》', '>', '、'
    )
    return trim()
        .trim('"', '\'', '“', '”', '「', '」', '『', '』', '《', '》', '<', '>', '【', '】', '[', ']', '(', ')')
        .takeWhile { it !in stopChars }
        .trim()
        .takeIf { it.length in 1..24 }
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
