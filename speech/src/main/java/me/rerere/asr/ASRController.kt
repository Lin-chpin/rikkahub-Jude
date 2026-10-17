package me.rerere.asr

import kotlinx.coroutines.flow.StateFlow

interface ASRController {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit) = start(onTranscriptChange, null)
    fun start(
        onTranscriptChange: (String) -> Unit,
        onUtterance: ((ASRUtterance) -> Unit)?,
    )
    fun stop()
    fun dispose()
}
