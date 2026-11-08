package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCompressionStateTest {
    private val assistantId = Uuid.random()

    @Test
    fun compressionKeepsOriginalNodesAndLeavesAppendedMessagesVisible() {
        val first = messageNode("A")
        val second = messageNode("B")
        val appended = messageNode("new")
        val compressionBase = conversationOf(first, second)
        val current = compressionBase.copy(
            messageNodes = compressionBase.messageNodes + appended,
        )

        val result = current.withCompressionResultIfBaseUnchanged(
            expectedSummary = compressionBase.compressedSummary,
            expectedCompressedNodeIds = compressionBase.activeCompressedMessageNodeIds,
            newSummary = "A and B summary",
            nodeIdsToCompress = setOf(first.id, second.id),
            newAutoCompressConfig = null,
        )

        assertNotNull(result)
        result!!
        assertEquals("A and B summary", result.compressedSummary)
        assertEquals(setOf(first.id, second.id), result.activeCompressedMessageNodeIds)
        assertEquals(listOf(appended.id), result.visibleMessageNodes.map { it.id })
        assertEquals(
            listOf(first.id, second.id, appended.id),
            result.messageNodes.map { it.id },
        )
    }

    @Test
    fun staleCompressionResultIsRejectedAfterSummaryChanges() {
        val first = messageNode("A")
        val second = messageNode("B")
        val base = conversationOf(first, second).copy(
            compressedSummary = "old summary",
            compressedMessageNodeIds = setOf(first.id),
        )
        val current = base.copy(compressedSummary = "edited summary")

        val result = current.withCompressionResultIfBaseUnchanged(
            expectedSummary = base.compressedSummary,
            expectedCompressedNodeIds = base.activeCompressedMessageNodeIds,
            newSummary = "stale merged summary",
            nodeIdsToCompress = setOf(second.id),
            newAutoCompressConfig = null,
        )

        assertNull(result)
    }

    @Test
    fun staleCompressionResultIsRejectedAfterAnotherCompressionChangesHiddenNodes() {
        val first = messageNode("A")
        val second = messageNode("B")
        val third = messageNode("C")
        val base = conversationOf(first, second, third).copy(
            compressedSummary = "A",
            compressedMessageNodeIds = setOf(first.id),
        )
        val current = base.copy(compressedMessageNodeIds = setOf(first.id, second.id))

        val result = current.withCompressionResultIfBaseUnchanged(
            expectedSummary = base.compressedSummary,
            expectedCompressedNodeIds = base.activeCompressedMessageNodeIds,
            newSummary = "A duplicated",
            nodeIdsToCompress = setOf(second.id),
            newAutoCompressConfig = null,
        )

        assertNull(result)
    }

    @Test
    fun emptyCompressionSummaryCannotHideMoreMessages() {
        val hidden = messageNode("A")
        val visible = messageNode("B")
        val current = conversationOf(hidden, visible).copy(
            compressedMessageNodeIds = setOf(hidden.id),
        )

        val result = current.withCompressionResultIfBaseUnchanged(
            expectedSummary = null,
            expectedCompressedNodeIds = setOf(hidden.id),
            newSummary = "  ",
            nodeIdsToCompress = setOf(visible.id),
            newAutoCompressConfig = null,
        )

        assertNull(result)
        assertEquals(null, current.compressedSummary)
        assertEquals(setOf(hidden.id), current.compressedMessageNodeIds)
    }

    @Test
    fun missingSummarySelectsOnlyPreviouslyCompressedNodesForRecovery() {
        val nodes = List(950) { index -> messageNode("Message $index") }
        val compressedNodes = nodes.take(916)
        val conversation = conversationOf(*nodes.toTypedArray()).copy(
            compressedSummary = "",
            compressedMessageNodeIds = compressedNodes.mapTo(mutableSetOf()) { it.id },
        )

        val recoveryNodes = conversation.compressionCandidates(
            visibleCandidates = nodes.drop(916),
            keepRecentMessages = 32,
            includeVisibleCandidates = false,
        )
        assertEquals(916, recoveryNodes.size)
        assertEquals(compressedNodes.map { it.id }.toSet(), recoveryNodes.map { it.id }.toSet())
        assertEquals(34, conversation.visibleMessageNodes.size)
        assertEquals(
            918,
            conversation.compressionCandidates(
                visibleCandidates = nodes.drop(916),
                keepRecentMessages = 32,
                includeVisibleCandidates = true,
            ).size,
        )

        assertEquals(
            emptyList<MessageNode>(),
            conversation.copy(compressedSummary = "restored").compressionCandidates(
                visibleCandidates = nodes.drop(916),
                keepRecentMessages = 32,
                includeVisibleCandidates = false,
            ),
        )
    }

    @Test
    fun normalizationKeepsLatestNodeVisibleWhenStoredIdsCoverEveryNode() {
        val first = messageNode("A")
        val second = messageNode("B")
        val latest = messageNode("C")
        val conversation = conversationOf(first, second, latest).copy(
            compressedSummary = "full summary",
            compressedMessageNodeIds = setOf(first.id, second.id, latest.id),
        )

        val normalized = conversation.normalizeCompressionState()

        assertEquals(setOf(first.id, second.id), normalized.compressedMessageNodeIds)
        assertEquals(listOf(latest.id), normalized.visibleMessageNodes.map { it.id })
        assertEquals("full summary", normalized.compressedSummary)
    }

    @Test
    fun deletingMessagePreservesFollowingNodesAndEarlierVariants() {
        val before = messageNode("before")
        val selected = UIMessage.user("A")
        val alternate = UIMessage.user("A alternate")
        val target = MessageNode(messages = listOf(selected, alternate), selectIndex = 0)
        val reply = MessageNode.of(UIMessage.assistant("B"))
        val tool = MessageNode.of(
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(UIMessagePart.Text("tool result")),
            )
        )
        val conversation = conversationOf(before, target, reply, tool).copy(
            compressedSummary = "before summary",
            compressedMessageNodeIds = setOf(before.id),
        )

        val updated = conversation.deleteMessageFromNode(selected.id)

        assertNotNull(updated)
        updated!!
        assertEquals(listOf(before.id, target.id, reply.id, tool.id), updated.messageNodes.map { it.id })
        assertEquals(listOf(alternate.id), updated.messageNodes[1].messages.map { it.id })
        assertEquals("before summary", updated.compressedSummary)
        assertEquals(setOf(before.id), updated.compressedMessageNodeIds)
    }

    @Test
    fun deletingCompressedMessageClearsSummaryContainingDeletedContext() {
        val before = messageNode("before")
        val target = messageNode("A")
        val reply = MessageNode.of(UIMessage.assistant("B"))
        val conversation = conversationOf(before, target, reply).copy(
            compressedSummary = "before, A and B",
            compressedMessageNodeIds = setOf(before.id, target.id),
        )

        val updated = conversation.deleteMessageFromNode(target.currentMessage.id)

        assertNotNull(updated)
        updated!!
        assertEquals(listOf(before.id, reply.id), updated.messageNodes.map { it.id })
        assertNull(updated.compressedSummary)
        assertEquals(emptySet<Uuid>(), updated.compressedMessageNodeIds)
    }

    @Test
    fun updatingAllCurrentMessagesAfterCompressionPreservesNodeStructure() {
        val first = messageNode("A")
        val second = messageNode("B")
        val visible = messageNode("C")
        val conversation = conversationOf(first, second, visible).copy(
            compressedSummary = "A and B summary",
            compressedMessageNodeIds = setOf(first.id, second.id),
        )

        val updated = conversation.updateCurrentMessages(
            conversation.currentMessages.map { message ->
                message.copy(parts = listOf(UIMessagePart.Text("updated")))
            }
        )

        assertEquals(conversation.messageNodes.map { it.id }, updated.messageNodes.map { it.id })
        assertEquals(listOf(1, 1, 1), updated.messageNodes.map { it.messages.size })
        assertEquals(setOf(first.id, second.id), updated.activeCompressedMessageNodeIds)
        assertEquals(listOf(visible.id), updated.visibleMessageNodes.map { it.id })
    }

    @Test
    fun streamingUpdateOnlyTouchesTheTargetVisibleNode() {
        val first = messageNode("A")
        val second = messageNode("B")
        val conversation = conversationOf(first, second)
        val updatedMessage = second.currentMessage.copy(
            parts = listOf(UIMessagePart.Text("updated")),
        )

        val updated = conversation.updateMessageAtNodeIndex(
            nodeIndex = conversation.visibleMessageNodeIndexAt(1),
            message = updatedMessage,
        )

        assertEquals(listOf(first.id, second.id), updated.messageNodes.map { it.id })
        assertEquals(first, updated.messageNodes.first())
        assertEquals(updatedMessage, updated.messageNodes[1].currentMessage)
    }

    @Test
    fun visibleForkPointRemapsCompressedNodeIdsAndKeepsSummary() {
        val first = messageNode("A")
        val second = messageNode("B")
        val target = messageNode("C")
        val conversation = conversationOf(first, second, target).copy(
            compressedSummary = "A and B summary",
            compressedMessageNodeIds = setOf(first.id, second.id),
        )
        val copiedFirstId = Uuid.random()
        val copiedSecondId = Uuid.random()

        val result = conversation.compressionStateForFork(
            targetNodeId = target.id,
            copiedNodeIdsBySourceId = mapOf(
                first.id to copiedFirstId,
                second.id to copiedSecondId,
                target.id to Uuid.random(),
            ),
        )

        assertEquals("A and B summary", result.summary)
        assertEquals(setOf(copiedFirstId, copiedSecondId), result.compressedNodeIds)
    }

    @Test
    fun forkInsideCompressedHistoryDropsSummaryThatMayContainLaterMessages() {
        val first = messageNode("A")
        val target = messageNode("B")
        val visible = messageNode("C")
        val conversation = conversationOf(first, target, visible).copy(
            compressedSummary = "A and B summary",
            compressedMessageNodeIds = setOf(first.id, target.id),
        )

        val result = conversation.compressionStateForFork(
            targetNodeId = target.id,
            copiedNodeIdsBySourceId = mapOf(
                first.id to Uuid.random(),
                target.id to Uuid.random(),
            ),
        )

        assertNull(result.summary)
        assertEquals(emptySet<Uuid>(), result.compressedNodeIds)
    }

    private fun conversationOf(vararg nodes: MessageNode) = Conversation(
        assistantId = assistantId,
        messageNodes = nodes.toList(),
    )

    private fun messageNode(text: String) = MessageNode.of(
        UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(text)),
        )
    )
}
