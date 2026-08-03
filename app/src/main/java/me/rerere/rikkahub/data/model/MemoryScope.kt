package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

@JvmInline
value class MemoryScope private constructor(val ownerId: String) {
    companion object {
        val Global = MemoryScope("__global__")

        fun assistant(assistantId: Uuid): MemoryScope = MemoryScope(assistantId.toString())
    }
}

val Assistant.memoryScope: MemoryScope
    get() = if (useGlobalMemory) MemoryScope.Global else MemoryScope.assistant(id)
