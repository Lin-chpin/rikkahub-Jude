package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryScope

class MemoryRepository(private val memoryDAO: MemoryDAO) {

    fun observeMemories(scope: MemoryScope): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(scope.ownerId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemories(scope: MemoryScope): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(scope.ownerId)
            .map { AssistantMemory(it.id, it.content) }
    }


    suspend fun deleteMemories(scope: MemoryScope) {
        memoryDAO.deleteMemoriesOfAssistant(scope.ownerId)
    }

    suspend fun updateMemory(scope: MemoryScope, id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryByIdInScope(id, scope.ownerId)
            ?: error("Memory record #$id not found in the requested scope")
        val newMemory = old.copy(
            content = content
        )
        memoryDAO.updateMemory(newMemory)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(scope: MemoryScope, content: String): AssistantMemory {
        val memory = AssistantMemory(
            id = 0,
            content = content,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = scope.ownerId,
                    content = memory.content
                )
            ).toInt()
        )
        return newMemory
    }

    suspend fun deleteMemory(scope: MemoryScope, id: Int): Boolean {
        return memoryDAO.deleteMemoryInScope(id, scope.ownerId) > 0
    }
}
