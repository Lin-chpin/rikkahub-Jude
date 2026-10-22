package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test fun lateCallbacksCannotInitializeOrReviveASessionAndFailedLoadsCanRetry() = runBlocking {
        val session = session(conversation("placeholder"))
        val loaded = conversation("stored")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { session.initializeState { error("read failure") } }
        }
        session.updateInitializedState { it.copy(title = "late callback") }
        assertEquals("placeholder", session.state.value.title)
        assertEquals(loaded, session.initializeState { loaded })
        session.markDeleted()
        session.updateInitializedState { it.copy(title = "deleted callback") }
        assertEquals(loaded, session.state.value)
        session.restoreState(loaded)
        session.updateInitializedState { it.copy(title = "restored") }
        assertEquals("restored", session.state.value.title)
    }

    @Test fun concurrentFieldMutationsPreserveBothWriters() = runBlocking {
        val session = session(conversation("0"))
        val title = async(Dispatchers.Default) {
            repeat(100) { session.updateState { it.copy(title = (it.title.toInt() + 1).toString()) } }
        }
        val suggestions = async(Dispatchers.Default) {
            repeat(100) { index -> session.updateState { it.copy(chatSuggestions = it.chatSuggestions + "$index") } }
        }
        title.await()
        suggestions.await()
        assertEquals("100", session.state.value.title)
        assertEquals(100, session.state.value.chatSuggestions.size)
    }

    @Test fun queuedPersistenceReadsLatestAndAnOlderCommitDoesNotPublishBackwards() = runBlocking {
        val session = session(conversation("original"))
        val writing = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<Conversation>()
        val first = async {
            session.withPersistence {
                val snapshot = session.updateState { it.copy(title = "title") }
                writing.complete(Unit)
                finishWrite.await()
                snapshots += snapshot
            }
        }
        writing.await()
        session.updateState { it.copy(chatSuggestions = listOf("new stream state")) }
        val second = async {
            session.withPersistence { snapshots += session.state.value }
        }
        yield()
        finishWrite.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("new stream state"), snapshots.last().chatSuggestions)
        assertEquals("title", session.state.value.title)
        assertEquals(snapshots.last(), session.state.value)
    }

    @Test fun deletionRejectsLateUpdates() {
        val session = session(conversation("kept"))
        session.markDeleted()
        assertThrows(IllegalStateException::class.java) { session.updateState { it.copy(title = "late") } }
        assertEquals("kept", session.state.value.title)
    }

    @Test fun aReplacedOrCancelledGenerationCannotPublishLateFrames() = runBlocking {
        val session = session(conversation("kept"))
        val old = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        session.setJob(old)
        yield()
        val replacement = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        session.setJob(replacement)
        session.updateGenerationState(old, allowCancelled = true) { it.copy(title = "old frame") }
        assertEquals("kept", session.state.value.title)
        replacement.cancel()
        session.updateGenerationState(replacement) { it.copy(title = "cancelled frame") }
        assertEquals("kept", session.state.value.title)
        replacement.join()
        old.join()
        session.cleanup()
    }

    @Test fun replacementIsInstalledBeforeStartingAndOldCompletionCannotClearIt() = runBlocking {
        val session = session(conversation("call"))
        val releaseOld = CompletableDeferred<Unit>()
        val old = launch(start = CoroutineStart.LAZY) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { releaseOld.await() } }
        }
        session.setJob(old)
        yield()
        val replacement = launch(start = CoroutineStart.LAZY) {
            assertSame(coroutineContext[Job], session.getJob())
            awaitCancellation()
        }
        session.setJob(replacement)
        releaseOld.complete(Unit)
        old.join()
        assertSame(replacement, session.getJob())
        replacement.cancel()
        replacement.join()
        session.cleanup()
    }

    @Test
    fun sessionStateIsLoadedOnlyOnce() = runBlocking {
        val initial = conversation("initial")
        val loaded = conversation("loaded")
        val session = session(initial)
        var loadCount = 0

        val first = session.initializeState {
            loadCount += 1
            loaded
        }
        val second = session.initializeState {
            loadCount += 1
            conversation("should not load")
        }

        assertEquals(1, loadCount)
        assertEquals(loaded, first)
        assertEquals(loaded, second)
        assertEquals(loaded, session.state.value)
    }

    @Test
    fun liveStateUpdateWinsOverRoomLoadAlreadyInProgress() = runBlocking {
        val initial = conversation("initial")
        val loaded = conversation("room")
        val live = conversation("live")
        val session = session(initial)
        val loadStarted = CompletableDeferred<Unit>()
        val allowLoadToFinish = CompletableDeferred<Unit>()

        val initialization = async {
            session.initializeState {
                loadStarted.complete(Unit)
                allowLoadToFinish.await()
                loaded
            }
        }

        loadStarted.await()
        session.replaceState(live)
        allowLoadToFinish.complete(Unit)

        assertEquals(live, initialization.await())
        assertEquals(live, session.state.value)
    }

    private fun session(initial: Conversation) = ConversationSession(
        id = initial.id,
        initial = initial,
        scope = CoroutineScope(SupervisorJob()),
        onIdle = {},
    )

    private fun conversation(title: String) = Conversation.ofId(
        id = Uuid.random(),
        assistantId = Uuid.random(),
    ).copy(title = title)
}
