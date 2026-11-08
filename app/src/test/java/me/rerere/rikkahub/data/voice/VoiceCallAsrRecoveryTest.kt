package me.rerere.rikkahub.data.voice

import me.rerere.asr.ASRUtterance
import org.junit.Assert.*
import org.junit.Test

class VoiceCallAsrRecoveryTest {
    @Test
    fun silentStableConnectionResetsTheFailureBudgetAfterThirtySeconds() {
        val recovery = VoiceCallAsrRecovery()
        recovery.startAttempt()
        recovery.listening(1_000)
        recovery.resetIfStable(30_999)
        assertEquals(1, recovery.attempts)
        recovery.resetIfStable(31_000)
        assertEquals(0, recovery.attempts)
        assertFalse(recovery.exhausted)
    }

    @Test
    fun shortConnectionsDoNotResetTheFailureBudget() {
        val recovery = VoiceCallAsrRecovery()
        repeat(3) { index ->
            recovery.startAttempt()
            recovery.listening(index * 10_000L)
            recovery.resetIfStable(index * 10_000L + 5_000)
            recovery.disconnected()
        }
        assertTrue(recovery.exhausted)
    }

    @Test
    fun unfinishedSpeechIsInvalidatedAndClearedOnlyAfterRecovery() {
        val recovery = VoiceCallAsrRecovery()
        val oldAttempt = recovery.startAttempt()
        recovery.accept(ASRUtterance("old", "我还没说完", false))
        recovery.disconnected()
        assertFalse(recovery.isCurrent(oldAttempt))
        val newAttempt = recovery.startAttempt()
        assertTrue(recovery.isCurrent(newAttempt))
        assertTrue(recovery.listening(1_000))
        assertFalse(recovery.listening(1_001))
    }

    @Test
    fun speechStartWithoutTextIsAlsoClearedAfterRecovery() {
        val recovery = VoiceCallAsrRecovery()
        recovery.startAttempt()
        recovery.accept(ASRUtterance("turn", "", false, isSpeechStarted = true))
        recovery.disconnected()
        recovery.startAttempt()
        assertTrue(recovery.listening(1_000))
    }

    @Test
    fun newSpeechDuringRecoveryReplacesTheStalePartial() {
        val recovery = VoiceCallAsrRecovery()
        recovery.startAttempt()
        recovery.accept(ASRUtterance("old", "旧分句", false))
        recovery.disconnected()
        recovery.startAttempt()
        recovery.accept(ASRUtterance("new", "新的问题", false))
        assertFalse(recovery.listening(1_000))
    }

    @Test
    fun emptyFinalFinishesSpeechWithoutKeepingAStaleTurn() {
        val recovery = VoiceCallAsrRecovery()
        recovery.startAttempt()
        recovery.accept(ASRUtterance("turn", "啊", false))
        recovery.accept(ASRUtterance("turn", "", true))
        recovery.disconnected()
        recovery.startAttempt()
        assertFalse(recovery.listening(1_000))
    }
}
