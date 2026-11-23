package me.rerere.rikkahub.ui.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputStateImportTest {
    @Test
    fun pendingFileImportsTrackConcurrentWorkAndNeverGoNegative() {
        val state = ChatInputState()

        state.beginFileImport()
        state.beginFileImport()
        assertEquals(2, state.pendingFileImports)

        state.finishFileImport()
        assertEquals(1, state.pendingFileImports)

        state.finishFileImport()
        state.finishFileImport()
        assertEquals(0, state.pendingFileImports)
    }
}
