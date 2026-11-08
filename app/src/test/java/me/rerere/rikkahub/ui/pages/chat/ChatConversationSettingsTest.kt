package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.mergeSettingsUpdate
import me.rerere.rikkahub.data.datastore.withDisplayedAssistant
import me.rerere.rikkahub.data.datastore.withUpdateBaseline
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ChatConversationSettingsTest {
    private val first = Assistant(
        id = Uuid.random(),
        name = "Conversation assistant",
        chatModelId = Uuid.random(),
        background = "file:///conversation-background.png",
    )
    private val second = Assistant(id = Uuid.random(), name = "Global selection")

    @Test
    fun restoredConversationUsesItsOwnAssistantBeforeGlobalSelectionCatchesUp() {
        val global = Settings(assistantId = second.id, assistants = listOf(first, second))
            .withUpdateBaseline()

        val displayed = global.withDisplayedAssistant(first.id)

        assertSame(first, displayed.getCurrentAssistant())
        assertEquals(first.chatModelId, displayed.getCurrentAssistant().chatModelId)
        assertEquals(first.background, displayed.getCurrentAssistant().background)
        assertEquals(second.id, global.assistantId)
        assertSame(global.assistants, displayed.assistants)
        assertSame(global.providers, displayed.providers)
    }

    @Test
    fun changingGlobalSelectionKeepsTheOldConversationUntilTheNewConversationIsReady() {
        val global = Settings(assistantId = first.id, assistants = listOf(first, second))
        val switched = global.copy(assistantId = second.id)

        assertSame(first, switched.withDisplayedAssistant(first.id).getCurrentAssistant())
        assertSame(second, switched.withDisplayedAssistant(second.id).getCurrentAssistant())
        assertEquals(second.id, switched.assistantId)
    }

    @Test
    fun matchingOrDeletedSessionAssistantPreservesExistingFallbackAndIdentity() {
        val global = Settings(assistantId = second.id, assistants = listOf(second))

        assertSame(global, global.withDisplayedAssistant(second.id))
        assertSame(global, global.withDisplayedAssistant(first.id))
        assertSame(second, global.withDisplayedAssistant(first.id).getCurrentAssistant())
    }

    @Test
    fun editingDisplayedSettingsPreservesGlobalSelectionAndConcurrentChanges() {
        val third = Assistant(id = Uuid.random(), name = "Later selection")
        val global = Settings(assistantId = second.id, assistants = listOf(first, second, third))
            .withUpdateBaseline()
        val displayed = global.withDisplayedAssistant(first.id)
        val requested = displayed.copy(
            displaySetting = displayed.displaySetting.copy(userNickname = "Edited name"),
        )

        listOf(second.id, third.id).forEach { selectedId ->
            val current = global.copy(
                assistantId = selectedId,
                displaySetting = global.displaySetting.copy(showUserAvatar = false),
                assistants = listOf(first.copy(systemPrompt = "Concurrent prompt"), second, third),
            )
            val result = mergeSettingsUpdate(current, requested)

            assertEquals(selectedId, result.assistantId)
            assertEquals("Edited name", result.displaySetting.userNickname)
            assertEquals(false, result.displaySetting.showUserAvatar)
            assertEquals(current.assistants, result.assistants)
        }
    }

    @Test
    fun displayingDefaultAssistantDoesNotPersistSelectionWhenEditingAnotherField() {
        val defaultAssistant = Settings().assistants.first()
        val global = Settings(
            assistantId = second.id,
            assistants = listOf(defaultAssistant, second),
        ).withUpdateBaseline()
        val displayed = global.withDisplayedAssistant(defaultAssistant.id)
        val result = mergeSettingsUpdate(global, displayed.copy(themeId = "edited-theme"))

        assertEquals(defaultAssistant.id, displayed.assistantId)
        assertEquals(second.id, result.assistantId)
        assertEquals("edited-theme", result.themeId)
    }
}
