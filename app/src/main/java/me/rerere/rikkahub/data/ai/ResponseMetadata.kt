package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage

/** Diagnostics keep actual API usage and timing, never response text, images or annotations. */
fun UIMessage.responseMetadata(): UIMessage = UIMessage(
    id = id, role = role, parts = emptyList(), modelId = modelId, usage = usage,
    createdAt = createdAt, finishedAt = finishedAt,
)
