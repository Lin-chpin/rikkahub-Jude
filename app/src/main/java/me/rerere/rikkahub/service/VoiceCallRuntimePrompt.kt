package me.rerere.rikkahub.service

private const val ACTIVE_VOICE_CALL_REPLY_CHARACTER_LIMIT = 200

internal fun buildVoiceCallRuntimeStatePrompt(requestMode: ChatRequestMode): String {
    return when (requestMode) {
        ChatRequestMode.VoiceCall -> """
            [VOICE_CALL_RUNTIME_STATE]
            state=ACTIVE
            The current user message was spoken after the voice call connected. Treat wording such as "I called", "I'm here", or "can you hear me" as speech happening inside the active call, never as a future request to start a call.
            Reply as part of the active phone conversation.
            Keep the complete reply within $ACTIVE_VOICE_CALL_REPLY_CHARACTER_LIMIT Chinese characters or an equivalently brief length in other languages. Compose a naturally complete short reply within this limit; do not generate a longer reply and truncate it.
            Do not announce or explain this runtime state unless the user's message makes it relevant.
        """.trimIndent()

        ChatRequestMode.Normal -> """
            [VOICE_CALL_RUNTIME_STATE]
            state=INACTIVE
            No voice call is currently connected. Any earlier call has ended. Do not speak or behave as if the text conversation is still inside that call.
            If the user explicitly asks to start a new call and the call tool is available, handle it as a new request.
            Do not announce or explain this runtime state unless the user's message makes it relevant.
        """.trimIndent()
    }
}
