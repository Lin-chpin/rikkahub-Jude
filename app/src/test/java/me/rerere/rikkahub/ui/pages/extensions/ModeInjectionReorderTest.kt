package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.lazy.LazyListItemInfo
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeInjectionReorderTest {
    private val prompts = List(8) { index ->
        PromptInjection.ModeInjection(name = "Prompt $index", content = "Content $index")
    }

    @Test
    fun movesFirstPromptToLastPastPinnedCard() {
        val original = prompts.toList()
        val result = reorderModeInjections(prompts, item(1, 0), item(8, 7))

        assertEquals(prompts.drop(1) + prompts.first(), result)
        assertEquals(original, prompts)
    }

    @Test
    fun movesLastPromptToFirstBelowPinnedCard() {
        val result = reorderModeInjections(prompts, item(8, 7), item(1, 0))

        assertEquals(listOf(prompts.last()) + prompts.dropLast(1), result)
    }

    @Test
    fun movesMiddlePromptInBothDirections() {
        assertEquals(
            listOf(0, 2, 3, 1, 4, 5, 6, 7).map(prompts::get),
            reorderModeInjections(prompts, item(2, 1), item(4, 3)),
        )
        assertEquals(
            listOf(0, 3, 1, 2, 4, 5, 6, 7).map(prompts::get),
            reorderModeInjections(prompts, item(4, 3), item(2, 1)),
        )
    }

    @Test
    fun resolvesKeysAgainstLatestListInsteadOfStaleLayoutIndices() {
        val edited = prompts[0].copy(content = "Edited while dragging")
        val latest = listOf(prompts[1], prompts[2], edited)

        assertEquals(
            listOf(edited, prompts[1], prompts[2]),
            reorderModeInjections(latest, item(1, 0), item(2, 1)),
        )
    }

    @Test
    fun ignoresPinnedCardAndRemovedEndpoints() {
        val pinned = DragItem(0, "elevenlabs-voice-worldbook")
        assertNull(reorderModeInjections(prompts, item(1, 0), pinned))
        assertNull(reorderModeInjections(prompts, pinned, item(1, 0)))
        assertNull(reorderModeInjections(prompts.drop(1), item(1, 0), item(2, 1)))
        assertNull(reorderModeInjections(prompts.dropLast(1), item(1, 0), item(8, 7)))
    }

    @Test
    fun ignoresEmptyListAndUnchangedPosition() {
        assertNull(reorderModeInjections(emptyList(), item(1, 0), item(2, 1)))
        assertNull(reorderModeInjections(listOf(prompts[0]), item(1, 0), item(1, 0)))
        assertNull(reorderModeInjections(prompts, item(4, 3), item(4, 3)))
    }

    private fun item(layoutIndex: Int, promptIndex: Int) =
        DragItem(layoutIndex, prompts[promptIndex].id)

    private data class DragItem(
        override val index: Int,
        override val key: Any,
    ) : LazyListItemInfo {
        override val offset: Int = 0
        override val size: Int = 1
        override val contentType: Any? = null
    }
}
