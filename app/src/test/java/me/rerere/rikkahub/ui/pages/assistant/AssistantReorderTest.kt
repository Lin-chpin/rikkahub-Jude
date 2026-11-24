package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.lazy.LazyListItemInfo
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantReorderTest {
    private val assistants = List(6) { index ->
        Assistant(name = "Assistant $index")
    }

    private val tags = List(5) { index ->
        Tag(id = Uuid.random(), name = "Tag $index")
    }

    @Test
    fun movesFirstAssistantToLastWithoutMutatingInput() {
        val original = assistants.toList()
        val result = reorderAssistants(assistants, assistantItem(0, 0), assistantItem(5, 5))

        assertEquals(assistants.drop(1) + assistants.first(), result)
        assertEquals(original, assistants)
    }

    @Test
    fun movesLastAssistantToFirst() {
        val result = reorderAssistants(assistants, assistantItem(5, 5), assistantItem(0, 0))

        assertEquals(listOf(assistants.last()) + assistants.dropLast(1), result)
    }

    @Test
    fun movesMiddleAssistantInBothDirections() {
        assertEquals(
            listOf(0, 2, 3, 1, 4, 5).map(assistants::get),
            reorderAssistants(assistants, assistantItem(1, 1), assistantItem(3, 3)),
        )
        assertEquals(
            listOf(0, 3, 1, 2, 4, 5).map(assistants::get),
            reorderAssistants(assistants, assistantItem(3, 3), assistantItem(1, 1)),
        )
    }

    @Test
    fun resolvesAssistantKeysAgainstLatestListInsteadOfStaleLayoutIndices() {
        val edited = assistants[0].copy(name = "Edited while dragging")
        val latest = listOf(assistants[1], assistants[2], edited)

        assertEquals(
            listOf(edited, assistants[1], assistants[2]),
            reorderAssistants(latest, assistantItem(0, 0), assistantItem(1, 1)),
        )
    }

    @Test
    fun ignoresMissingOrUnchangedAssistantEndpoints() {
        val unknown = DragItem(0, Uuid.random())
        assertNull(reorderAssistants(assistants, assistantItem(0, 0), unknown))
        assertNull(reorderAssistants(assistants, unknown, assistantItem(0, 0)))
        assertNull(reorderAssistants(assistants.drop(1), assistantItem(0, 0), assistantItem(1, 1)))
        assertNull(reorderAssistants(emptyList(), assistantItem(0, 0), assistantItem(1, 1)))
        assertNull(reorderAssistants(listOf(assistants[0]), assistantItem(0, 0), assistantItem(0, 0)))
        assertNull(reorderAssistants(assistants, assistantItem(2, 2), assistantItem(2, 2)))
    }

    @Test
    fun reordersAssistantTagsInBothDirectionsAndIgnoresInvalidMoves() {
        val original = tags.toList()
        assertEquals(
            tags.drop(1) + tags.first(),
            reorderAssistantTags(tags, tagItem(0, 0), tagItem(4, 4)),
        )
        assertEquals(
            listOf(tags.last()) + tags.dropLast(1),
            reorderAssistantTags(tags, tagItem(4, 4), tagItem(0, 0)),
        )
        assertEquals(
            listOf(0, 2, 1, 3, 4).map(tags::get),
            reorderAssistantTags(tags, tagItem(1, 1), tagItem(2, 2)),
        )
        assertEquals(original, tags)

        assertNull(reorderAssistantTags(tags, tagItem(1, 1), tagItem(1, 1)))
        assertNull(reorderAssistantTags(tags, tagItem(0, 0), DragItem(1, Uuid.random())))
        assertNull(reorderAssistantTags(emptyList(), tagItem(0, 0), tagItem(1, 1)))
    }

    private fun assistantItem(layoutIndex: Int, assistantIndex: Int) =
        DragItem(layoutIndex, assistants[assistantIndex].id)

    private fun tagItem(layoutIndex: Int, tagIndex: Int) =
        DragItem(layoutIndex, tags[tagIndex].id)

    private data class DragItem(
        override val index: Int,
        override val key: Any,
    ) : LazyListItemInfo {
        override val offset: Int = 0
        override val size: Int = 1
        override val contentType: Any? = null
    }
}
