package me.rerere.rikkahub.data.voice

import me.rerere.asr.ASRUtterance

internal data class VoiceCallInputAction(val interrupt: Boolean, val submittedText: String?)

/** Consume provider utterance IDs, never substring an accumulated transcript to guess a new turn. */
internal class VoiceCallUtteranceGate {
    private val completed = mutableSetOf<String>()
    private var interruptedId: String? = null
    private val order = mutableMapOf<String, Int>()
    private var newestMeaningfulOrder = -1

    fun accept(utterance: ASRUtterance): VoiceCallInputAction? {
        if (utterance.id in completed) return null
        val utteranceOrder = order.getOrPut(utterance.id) { order.size }
        if (utteranceOrder < newestMeaningfulOrder) return null
        if (utterance.isFinal) completed += utterance.id
        val text = utterance.text.trim()
        if (!text.isMeaningfulCallInput()) return VoiceCallInputAction(false, null)
        newestMeaningfulOrder = utteranceOrder
        val interrupt = interruptedId != utterance.id
        interruptedId = utterance.id
        return VoiceCallInputAction(interrupt, text.takeIf { utterance.isFinal })
    }
}

private fun String.isMeaningfulCallInput(): Boolean {
    val words = lowercase().filter { it.isLetterOrDigit() }
    if (words.isEmpty()) return false
    if (words in setOf("停", "等", "不", "别", "好", "是", "对", "行", "要", "能", "stop", "wait", "no")) return true
    if (words.all { it in "嗯啊呃哦唔哼" }) return false
    return words !in setOf("um", "uh", "erm", "hmm", "hm", "mm") &&
        (words.length >= 2)
}
