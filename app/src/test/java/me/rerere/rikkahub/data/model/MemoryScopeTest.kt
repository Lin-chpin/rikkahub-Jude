package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryScopeTest {
    @Test
    fun assistantMemoryIsIsolatedByDefault() {
        val assistantId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            useGlobalMemory = false,
        )

        assertEquals(assistantId.toString(), assistant.memoryScope.ownerId)
    }

    @Test
    fun globalMemoryIsUsedOnlyWhenExplicitlyEnabled() {
        val assistant = Assistant(useGlobalMemory = true)

        assertEquals(MemoryScope.Global, assistant.memoryScope)
        assertEquals("__global__", assistant.memoryScope.ownerId)
    }
}
