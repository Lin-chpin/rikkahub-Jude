package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCallInviteDeadlineTest {
    @Test fun openingThePageLateDoesNotStartAnotherThirtySeconds() {
        val invite = VoiceCallInviteDeadline("call", "conversation", initiatedAtMillis = 1_000)
        assertEquals(30_000, invite.remainingMillis(1_000))
        assertEquals(5_000, invite.remainingMillis(26_000))
        assertEquals(0, invite.remainingMillis(31_000))
        assertEquals("missed", invite.effectiveStatus(31_000))
        assertEquals("answered", invite.copy(status = "answered").effectiveStatus(90_000))
        assertEquals("declined", invite.copy(status = "declined").effectiveStatus(90_000))
    }
}
