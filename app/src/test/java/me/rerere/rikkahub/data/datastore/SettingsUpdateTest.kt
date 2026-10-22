package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.usagetracker.UsageReminderLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsUpdateTest {
    @Test
    fun staleUiCopyKeepsNewLocksAndUnrelatedNestedPreferences() {
        val baseline = Settings().withUpdateBaseline()
        val lock = UsageReminderLock(lockedUntilMillis = 1234, targetPackageName = "example.app")
        val current = baseline.copy(
            usageReminderState = baseline.usageReminderState.withLocks(mapOf(lock.storageKey() to lock)),
            displaySetting = baseline.displaySetting.copy(showUserAvatar = false),
        )
        val requested = baseline.copy(displaySetting = baseline.displaySetting.copy(userNickname = "New name"))
        val result = mergeSettingsUpdate(current, requested)

        assertEquals(current.usageReminderState, result.usageReminderState)
        assertFalse(result.displaySetting.showUserAvatar)
        assertEquals("New name", result.displaySetting.userNickname)
    }

    @Test
    fun sameAssistantFieldsMergeWithoutLosingOtherAssistantsOrListEdits() {
        val first = Assistant(id = Uuid.random(), name = "First")
        val removed = Assistant(id = Uuid.random(), name = "Remove")
        val concurrent = Assistant(id = Uuid.random(), name = "Concurrent")
        val added = Assistant(id = Uuid.random(), name = "Added")
        val baseline = Settings(assistants = listOf(first, removed)).withUpdateBaseline()
        val current = baseline.copy(assistants = listOf(first.copy(systemPrompt = "New prompt"), removed, concurrent))
        val requested = baseline.copy(assistants = listOf(first.copy(name = "Renamed"), added))
        val result = mergeSettingsUpdate(current, requested)

        assertEquals(listOf(first.id, added.id, concurrent.id), result.assistants.map { it.id })
        assertEquals("Renamed", result.assistants.first().name)
        assertEquals("New prompt", result.assistants.first().systemPrompt)
    }

    @Test
    fun staleEditorDoesNotResurrectDeletedAssistant() {
        val first = Assistant(id = Uuid.random(), name = "First")
        val baseline = Settings(assistants = listOf(first)).withUpdateBaseline()
        val result = mergeSettingsUpdate(
            baseline.copy(assistants = emptyList()),
            baseline.copy(assistants = listOf(first.copy(name = "Too late"))),
        )
        assertEquals(emptyList<Assistant>(), result.assistants)
    }

    @Test
    fun assistantEditUsesItsActualBaselineAndKeepsConcurrentAvatarAndPromptChanges() {
        val original = Assistant(id = Uuid.random(), name = "Old", avatar = Avatar.Image("file:///old.png"))
        val currentAssistant = original.copy(avatar = Avatar.Image("file:///new.png"), systemPrompt = "New prompt")
        val current = Settings(assistants = listOf(currentAssistant))
        val edited = original.copy(name = "Renamed")
        val result = mergeSettingsUpdate(current, current.withAssistantUpdate(edited, original))

        assertEquals(currentAssistant.copy(name = "Renamed"), result.assistants.single())
        val deleted = current.copy(assistants = emptyList())
        assertEquals(emptyList<Assistant>(), mergeSettingsUpdate(deleted, deleted.withAssistantUpdate(edited, original)).assistants)
    }

    @Test
    fun listsWithoutIdsReplaceAsOneSettingAndExplicitRestoreReplacesEverything() {
        val baseline = Settings(favoriteModels = listOf(Uuid.random())).withUpdateBaseline()
        val requested = baseline.copy(favoriteModels = listOf(Uuid.random()))
        val current = baseline.copy(favoriteModels = listOf(Uuid.random()), themeId = "other")
        assertEquals(requested.favoriteModels, mergeSettingsUpdate(current, requested).favoriteModels)
        val restored = Settings(themeId = "restored")
        assertEquals(restored, mergeSettingsUpdate(current, restored))
        assertFalse(JsonInstant.encodeToJsonElement(baseline).jsonObject.containsKey("updateBaseline"))
    }
}
