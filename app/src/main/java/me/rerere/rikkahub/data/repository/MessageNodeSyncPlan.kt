package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

internal data class MessageNodeSyncPlan(
    val inserts: List<MessageNodeEntity>,
    val updates: List<MessageNodeEntity>,
    val deletes: List<String>,
    val ftsNodeIds: Set<String>,
)

internal fun createMessageNodeSyncPlan(
    currentNodes: List<MessageNodeEntity>,
    updatedNodes: List<MessageNodeEntity>,
): MessageNodeSyncPlan {
    val currentById = currentNodes.associateBy(MessageNodeEntity::id)
    val updatedById = updatedNodes.associateBy(MessageNodeEntity::id)
    val inserts = updatedById.values.filter { it.id !in currentById }
    val updates = updatedById.values.filter { updated ->
        currentById[updated.id]?.let { current -> current != updated } == true
    }
    val deletes = currentById.keys.filter { it !in updatedById }
    val ftsNodeIds = buildSet {
        inserts.forEach { add(it.id) }
        updates.forEach { updated ->
            val current = currentById[updated.id]
            if (
                current == null ||
                current.messages != updated.messages ||
                current.conversationId != updated.conversationId
            ) {
                add(updated.id)
            }
        }
        addAll(deletes)
    }

    return MessageNodeSyncPlan(
        inserts = inserts,
        updates = updates,
        deletes = deletes,
        ftsNodeIds = ftsNodeIds,
    )
}
