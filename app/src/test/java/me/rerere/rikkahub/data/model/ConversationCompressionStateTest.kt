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
