package me.rerere.rikkahub.ui.components.ui

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionSelectorSelectionTest {
    @Test
    fun selectingFolderAddsMissingBooksAndKeepsOutsideSelections() {
        val firstBook = Uuid.random()
        val secondBook = Uuid.random()
        val outsideBook = Uuid.random()

        val selected = updateLorebookSelectionIds(
            selectedIds = setOf(firstBook, outsideBook),
            changedIds = setOf(firstBook, secondBook),
            checked = true,
        )

        assertEquals(setOf(firstBook, secondBook, outsideBook), selected)
        assertEquals(
            selected,
            updateLorebookSelectionIds(selected, setOf(firstBook, secondBook), checked = true),
        )
    }

    @Test
    fun uncheckingSingleBookKeepsOtherSelections() {
        val firstBook = Uuid.random()
        val secondBook = Uuid.random()

        assertEquals(
            setOf(secondBook),
            updateLorebookSelectionIds(setOf(firstBook, secondBook), setOf(firstBook), checked = false),
        )
    }
}
