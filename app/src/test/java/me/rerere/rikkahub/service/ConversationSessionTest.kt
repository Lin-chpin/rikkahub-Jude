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
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
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
