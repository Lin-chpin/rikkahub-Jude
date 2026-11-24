package me.rerere.rikkahub.ui.pages.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AssistantVMTest {
    @Test
    fun builtInAssistantCanBeRemovedAndSelectionMovesToRemainingAssistant() {
        val first = DEFAULT_ASSISTANTS[0]
        val second = DEFAULT_ASSISTANTS[1]
        val settings = Settings(assistantId = first.id, assistants = listOf(first, second))

        val updated = settings.withAssistantRemoved(first.id)

        assertEquals(listOf(second), updated?.assistants)
        assertEquals(second.id, updated?.assistantId)
    }

    @Test
    fun lastAssistantCannotBeRemoved() {
        val onlyAssistant = DEFAULT_ASSISTANTS[0]
        val settings = Settings(assistantId = onlyAssistant.id, assistants = listOf(onlyAssistant))

        assertNull(settings.withAssistantRemoved(onlyAssistant.id))
    }

    @Test
    fun memoryFlowCacheReusesFlowsByScopeAcrossRecompositions() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val observedScopes = mutableListOf<MemoryScope>()
            val firstAssistant = Assistant(name = "First", enableMemory = true)
            val secondAssistant = Assistant(name = "Second", enableMemory = true)
            val globalFirst = firstAssistant.copy(useGlobalMemory = true)
            val globalSecond = secondAssistant.copy(useGlobalMemory = true)

            val cache = AssistantMemoryFlowCache(scope) { memoryScope ->
                observedScopes += memoryScope
                flowOf(listOf(AssistantMemory(id = 1, content = memoryScope.ownerId)))
            }

            val firstFlow1 = cache.get(firstAssistant)
            val firstFlow2 = cache.get(firstAssistant.copy(name = "Renamed"))
            assertSame(firstFlow1, firstFlow2)

            val secondFlow = cache.get(secondAssistant)
            assertNotSame(firstFlow1, secondFlow)

            val globalFlow1 = cache.get(globalFirst)
            val globalFlow2 = cache.get(globalSecond)
            assertSame(globalFlow1, globalFlow2)
            assertNotSame(firstFlow1, globalFlow1)

            assertEquals(3, observedScopes.size)
            assertEquals(
                listOf(AssistantMemory(id = 1, content = firstAssistant.id.toString())),
                firstFlow1.first { it.isNotEmpty() },
            )
            assertEquals(
                listOf(AssistantMemory(id = 1, content = MemoryScope.Global.ownerId)),
                globalFlow1.first { it.isNotEmpty() },
            )
        } finally {
            scope.cancel()
        }
    }
}
