package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagFormat
import me.rerere.rikkahub.data.voice.VoiceCallAudioTagMode
import me.rerere.rikkahub.data.ai.prompts.buildVoiceCallRealtimeAudioTagPrompt

private const val ACTIVE_VOICE_CALL_REPLY_CHARACTER_LIMIT = 200

internal enum class VoiceCallRuntimeState {
    ACTIVE,
    ENDED,
    INACTIVE,
}

internal data class VoiceCallRuntimeContext(
    val systemPrompt: String,
)

internal fun buildVoiceCallAudioTagPrompt(
    mode: VoiceCallAudioTagMode,
    format: VoiceCallAudioTagFormat?,
): String = when {
    mode == VoiceCallAudioTagMode.REALTIME_MODEL && format != null ->
        buildVoiceCallRealtimeAudioTagPrompt(format)

    else -> """
        [VOICE_CALL_AUDIO_TAG_MODE]
        mode=${mode.name}
        Do not output audio tags, emotion labels, sound directions, protocol markers, or JSON. Reply with spoken text only.
    """.trimIndent()
}

internal fun buildVoiceCallToolTagStatus(
    mode: VoiceCallAudioTagMode,
    format: VoiceCallAudioTagFormat?,
): String = """
    Voice-call state: ACTIVE. The call is connected.
    Follow the current voice-call audio-tag mode exactly: ${mode.name}.
    ${buildVoiceCallAudioTagPrompt(mode, format)}
""".trimIndent()

internal fun ChatRequestMode.defaultVoiceCallRuntimeState(): VoiceCallRuntimeState =
    when (this) {
        ChatRequestMode.VoiceCall -> VoiceCallRuntimeState.ACTIVE
        ChatRequestMode.Normal -> VoiceCallRuntimeState.INACTIVE
    }

internal fun buildVoiceCallRuntimeContext(state: VoiceCallRuntimeState): VoiceCallRuntimeContext {
    return when (state) {
        VoiceCallRuntimeState.ACTIVE -> VoiceCallRuntimeContext(
            systemPrompt = """
            [VOICE_CALL_RUNTIME_STATE]
            state=ACTIVE
            A voice call is connected now, regardless of whether the user or the assistant initiated it.
            The current user message was spoken after the voice call connected. Treat wording such as "I called", "I'm here", or "can you hear me" as speech happening inside the active call, never as a future request to start a call.
            Reply as part of the active phone conversation.
            Speak in the first person and output only words you would actually say to the user on the phone.
            Do not include inner thoughts, private mental state, actions, expressions, environment details, narration, stage directions, or other non-spoken descriptions.
            If the active audio-tag mode requires audio tags, keep only the required protocol tags; do not turn them into narration or extra descriptions.
            Keep the complete reply within $ACTIVE_VOICE_CALL_REPLY_CHARACTER_LIMIT Chinese characters or an equivalently brief length in other languages. Compose a naturally complete short reply within this limit; do not generate a longer reply and truncate it.
            Do not announce or explain this runtime state unless the user's message makes it relevant.
            """.trimIndent(),
        )

        VoiceCallRuntimeState.ENDED -> VoiceCallRuntimeContext(
            systemPrompt = """
                [VOICE_CALL_RUNTIME_STATE]
                state=ENDED
                The previously active voice call has just been disconnected.
                The current user message is the first normal text message after hangup. Answer that message with awareness that the call is no longer active.
                Acknowledge the hangup only when it is natural and relevant to the user's message.
                Do not use audio or emotion tags, do not wait for more voice input, and do not restart the call unless the user explicitly makes a new request later.
            """.trimIndent(),
        )

        VoiceCallRuntimeState.INACTIVE -> VoiceCallRuntimeContext(
            systemPrompt = """
            [VOICE_CALL_RUNTIME_STATE]
            state=INACTIVE
            No voice call is currently connected. Any earlier call has ended. Do not speak or behave as if the text conversation is still inside that call.
            If the user explicitly asks to start a new call and the call tool is available, handle it as a new request.
            Do not announce or explain this runtime state unless the user's message makes it relevant.
            """.trimIndent(),
        )
    }
}

internal fun UIMessage.withVoiceCallRuntimeInstructionForRequest(
    state: VoiceCallRuntimeState,
    includeConnectionEvent: Boolean,
): UIMessage {
    if (role != MessageRole.USER || state == VoiceCallRuntimeState.INACTIVE) return this

    val prefix = buildString {
        appendLine("[VOICE_CALL_RUNTIME_TURN]")
        appendLine("state=${state.name}")
        appendLine("Speak in the first person and output only words you would actually say to the user on the phone.")
        appendLine("Do not include inner thoughts, private mental state, actions, expressions, environment details, narration, stage directions, or other non-spoken descriptions.")
        appendLine("If audio tags are required by the active audio-tag mode, keep only the required protocol tags and do not turn them into narration.")
        if (includeConnectionEvent) {
            val eventDescription = when (state) {
                VoiceCallRuntimeState.ACTIVE ->
                    "The voice call is connected. The following is the user's first spoken message after connection, regardless of who initiated the call."

                VoiceCallRuntimeState.ENDED ->
                    "The voice call has ended. The following is the user's first normal text message after hangup."

                VoiceCallRuntimeState.INACTIVE -> return@buildString
            }
            appendLine()
            appendLine("[VOICE_CALL_RUNTIME_EVENT]")
            appendLine("$eventDescription")
        }
        appendLine()
        appendLine("[USER_MESSAGE]")
    }
    var decoratedTextPart = false
    val decoratedParts = parts.map { part ->
        if (part is UIMessagePart.Text && !decoratedTextPart) {
            decoratedTextPart = true
            part.copy(text = "$prefix${part.text}")
        } else {
            part
        }
    }
    return copy(
        parts = if (decoratedTextPart) {
            decoratedParts
        } else {
            listOf(UIMessagePart.Text(prefix)) + decoratedParts
        }
    )
}
