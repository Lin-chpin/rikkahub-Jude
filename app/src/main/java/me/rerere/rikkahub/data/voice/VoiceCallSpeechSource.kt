package me.rerere.rikkahub.data.voice

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** Transient speech input; generation and tagging finish independently of Room and UI updates. */
internal data class VoiceCallSpeechInput(
    val generationJob: Job? = null,
    val message: UIMessage? = null,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val tagMode: VoiceCallAudioTagMode = VoiceCallAudioTagMode.DISABLED,
    val tagFormat: VoiceCallAudioTagFormat? = null,
) {
    fun displayText(): String = if (tagMode == VoiceCallAudioTagMode.REALTIME_MODEL) {
        message?.toText().orEmpty().withoutVoiceCallRealtimeEmotionMarker()
    } else {
        message?.voiceCallDisplayTextOrPlainText().orEmpty().withoutVoiceCallRealtimeEmotionMarker()
    }

    fun speechText(): String {
        val reply = message ?: return ""
        return when {
            tagMode == VoiceCallAudioTagMode.REALTIME_MODEL -> reply.toText()
                .withoutIncompleteVoiceCallAudioTag()
                .withoutVoiceCallRealtimeEmotionMarker()
            tagMode == VoiceCallAudioTagMode.SECOND_PASS && tagFormat != null &&
                !reply.hasVoiceCallAudioTagMetadata() && !finished -> ""
            else -> reply.voiceCallSpeechTextOrPlainText().withoutVoiceCallRealtimeEmotionMarker()
        }
    }
}

internal class VoiceCallSpeechSource {
    private val input = MutableStateFlow(VoiceCallSpeechInput())
    val state = input.asStateFlow()

    fun begin(
        generationJob: Job,
        historyMessageIds: Set<Uuid>,
        tagMode: VoiceCallAudioTagMode,
        tagFormat: VoiceCallAudioTagFormat?,
    ): Reply {
        generationJob.ensureActive()
        input.value = VoiceCallSpeechInput(generationJob = generationJob, tagMode = tagMode, tagFormat = tagFormat)
        return Reply(generationJob, historyMessageIds)
    }

    inner class Reply internal constructor(
        private val generationJob: Job,
        private val historyMessageIds: Set<Uuid>,
    ) {
        fun currentReply(messages: List<UIMessage>): UIMessage? = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.id !in historyMessageIds
        }

        fun publish(messages: List<UIMessage>) {
            currentReply(messages)?.let { reply ->
                input.update { if (it.generationJob === generationJob && !it.finished) it.copy(message = reply) else it }
            }
        }

        fun publishTagged(reply: UIMessage) {
            input.update {
                if (it.generationJob === generationJob && !it.finished && it.message?.id == reply.id) {
                    it.copy(message = reply)
                } else it
            }
        }

        fun finish(cancelled: Boolean = false) {
            input.update { if (it.generationJob === generationJob) it.copy(finished = true, cancelled = cancelled) else it }
        }
    }
}
