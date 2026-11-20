package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.data.model.Lorebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookFolderActionsTest {
    @Test
    fun reordersBooksByIdWithinTheirFolderAndPreservesOtherSlots() {
        val folderId = Uuid.random()
        val otherFolderId = Uuid.random()
        val ungrouped = Lorebook(name = "Ungrouped")
        val first = Lorebook(name = "First", folderId = folderId)
        val other = Lorebook(name = "Other folder", folderId = otherFolderId)
        val last = Lorebook(name = "Last", folderId = folderId)
        val books = listOf(ungrouped, first, other, last)

        val reordered = reorderLorebooks(books, last.id, first.id)

        assertEquals(listOf(ungrouped, last, other, first), reordered)
        assertEquals(listOf(ungrouped, first, other, last), books)
        assertNull(reorderLorebooks(books, first.id, other.id))
        assertNull(reorderLorebooks(books, Uuid.random(), first.id))
    }

    @Test
    fun assigningBooksByIdPreservesTheirOrderAndOtherFolders() {
        val folderId = Uuid.random()
        val otherFolderId = Uuid.random()
        val first = Lorebook(name = "First")
        val other = Lorebook(name = "Other", folderId = otherFolderId)
        val last = Lorebook(name = "Last")
        val books = listOf(first, other, last)

        val assigned = assignLorebooksToFolder(
            lorebooks = books,
            selectedIds = setOf(first.id.toString(), last.id.toString()),
            folderId = folderId,
        )

        assertEquals(listOf(first.copy(folderId = folderId), other, last.copy(folderId = folderId)), assigned)
        assertEquals(listOf(first, other, last), books)

        assertEquals(
            listOf(first, other, last.copy(folderId = folderId)),
            assignLorebooksToFolder(assigned, setOf(first.id.toString()), null),
        )
    }
}
