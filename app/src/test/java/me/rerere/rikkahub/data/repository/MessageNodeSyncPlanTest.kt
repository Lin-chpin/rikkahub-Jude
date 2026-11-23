package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNodeSyncPlanTest {
    @Test
    fun identicalSnapshotProducesNoDatabaseOrSearchIndexWrites() {
        val nodes = listOf(node("first", 0, "messages"), node("second", 1, "messages"))

        val plan = createMessageNodeSyncPlan(nodes, nodes)

        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.deletes.isEmpty())
        assertTrue(plan.ftsNodeIds.isEmpty())
    }

    @Test
    fun plansInsertUpdateDeleteAndReorderWithoutReindexingUnchangedMessageContent() {
        val currentNodes = listOf(
            node("first", 0, "first messages"),
            node("second", 1, "all branch variants", selectIndex = 0),
            node("removed", 2, "removed messages"),
        )
        val updatedNodes = listOf(
            node("second", 0, "all branch variants", selectIndex = 1),
            node("first", 1, "edited messages"),
            node("added", 2, "new messages"),
        )

        val plan = createMessageNodeSyncPlan(currentNodes, updatedNodes)

        assertEquals(listOf("added"), plan.inserts.map { it.id })
        assertEquals(listOf("second", "first"), plan.updates.map { it.id })
        assertEquals(listOf("removed"), plan.deletes)
        assertEquals(setOf("added", "first", "removed"), plan.ftsNodeIds)
    }

    @Test
    fun messageEditsOnlyReindexNodesWhoseSerializedMessagesChanged() {
        val currentNodes = listOf(
            node("edited", 0, "before"),
            node("unchanged", 1, "same"),
        )
        val updatedNodes = listOf(
            node("edited", 0, "after"),
            node("unchanged", 1, "same"),
        )

        val plan = createMessageNodeSyncPlan(currentNodes, updatedNodes)

        assertEquals(listOf("edited"), plan.updates.map { it.id })
        assertEquals(setOf("edited"), plan.ftsNodeIds)
    }

    private fun node(
        id: String,
        index: Int,
        messages: String,
        selectIndex: Int = 0,
    ) = MessageNodeEntity(
        id = id,
        conversationId = "conversation",
        nodeIndex = index,
        messages = messages,
        selectIndex = selectIndex,
    )
}
