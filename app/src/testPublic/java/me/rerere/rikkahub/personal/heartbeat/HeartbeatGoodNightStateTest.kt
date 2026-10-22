package me.rerere.rikkahub.personal.heartbeat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeartbeatGoodNightStateTest {
    @Test fun threeSilentRunsExitWithoutReplayingTheOriginalGoodNight() {
        var state = HeartbeatGoodNightState(active = true)
        repeat(2) { index ->
            state = state.afterHeartbeat(emptyList())
            assertEquals(index + 1, state.noUsageRuns)
        }
        state = state.afterHeartbeat(emptyList())
        assertFalse(state.active)
        assertEquals(0, state.noUsageRuns)
        assertEquals(state, state.afterHeartbeat(listOf(UIMessage.user("晚安"))))
    }

    @Test fun onlyASuccessfulLockInTheCurrentRunResetsTheCounter() {
        val state = HeartbeatGoodNightState(active = true, noUsageRuns = 2)
        assertEquals(HeartbeatGoodNightState(active = true), state.afterHeartbeat(lockReply(true)))
        assertFalse(state.afterHeartbeat(lockReply(false)).active)
        assertFalse(state.afterHeartbeat(lockReply(null)).active)
        assertFalse(state.afterHeartbeat(lockReply(true, action = "status")).active)
    }

    private fun lockReply(success: Boolean?, action: String = "lock") = listOf(
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "lock",
            toolName = "usage_lock_control",
            input = """{"action":"$action"}""",
            output = success?.let { listOf(UIMessagePart.Text("""{"success":$it}""")) }.orEmpty(),
        ))),
    )
}
