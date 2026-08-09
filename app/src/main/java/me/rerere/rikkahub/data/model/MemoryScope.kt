package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

@JvmInline
value class MemoryScope private constructor(val ownerId: String) {
    companion object {
        val Global = MemoryScope("__global__")

        fun assistant(assistantId: Uuid): MemoryScope = MemoryScope(assistantId.toString())

        /** Conversation keys are prefixed so they can never collide with legacy assistant UUID keys. */
        fun conversation(conversationId: Uuid): MemoryScope =
            MemoryScope("conversation:$conversationId")
    }
}

val Assistant.memoryScope: MemoryScope
    get() = memoryScope(conversationId = null)

fun Assistant.memoryScope(conversationId: Uuid?): MemoryScope = when {
    useConversationMemory && conversationId != null -> MemoryScope.conversation(conversationId)
    useGlobalMemory -> MemoryScope.Global
    else -> MemoryScope.assistant(id)
}
