package me.rerere.ai.util

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap

/** Logcat-only timing metadata; no text, UI events or persisted message annotations. */
class GenerationTimingTrace(
    private val scope: String,
    private val emit: (String) -> Unit = { Log.i("GenerationTiming", it) },
) {
    private val startedAt = System.nanoTime()
    private val traceId = startedAt.toString(16)
    private val recordedStages = ConcurrentHashMap.newKeySet<String>()

    fun firstText(stage: String, message: UIMessage?) {
        if (stage in recordedStages) return
        firstText(stage, message?.id?.toString(), message?.textCharacterCount() ?: 0)
    }

    fun firstText(stage: String, messageId: String?, characters: Int) {
        if (characters > 0) markOnce(stage, messageId, characters)
    }

    fun markOnce(stage: String, messageId: String? = null, characters: Int? = null) {
        if (recordedStages.add(stage)) mark(stage, messageId, characters)
    }

    fun mark(stage: String, messageId: String? = null, characters: Int? = null) {
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        emit(
            "trace=$traceId scope=$scope +${elapsedMillis}ms stage=$stage " +
                "messageId=${messageId ?: "none"} chars=${characters ?: 0}",
        )
    }
}

fun UIMessage.textCharacterCount(): Int = parts.sumOf { part ->
    if (part is UIMessagePart.Text) part.text.length else 0
}
