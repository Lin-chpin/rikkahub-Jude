package me.rerere.rikkahub.ui.pages.assistant

import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
