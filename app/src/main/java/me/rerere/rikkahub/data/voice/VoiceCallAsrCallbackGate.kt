package me.rerere.rikkahub.data.voice

internal class VoiceCallAsrCallbackGate {
    @Volatile
    private var accepting = false

    fun startAccepting() {
        accepting = true
    }

    fun stopAccepting() {
        accepting = false
    }

    fun canAcceptCallback(): Boolean = accepting

    fun stopAsrIfAccepting(stopAsr: () -> Unit) {
        val wasAccepting = accepting
        // Some ASR providers flush a final utterance from stop(); close the gate first so it cannot interrupt a reply.
        accepting = false
        if (wasAccepting) stopAsr()
    }
}
