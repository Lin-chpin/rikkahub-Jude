package me.rerere.rikkahub.service

import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings

private const val VOICE_CALL_SUMMARY_TARGET_TOKENS = 500

private const val VOICE_CALL_SUMMARY_INSTRUCTIONS = """
仅总结这一通已结束的语音通话。按时间顺序概括事情经过，保留重要事实、决定、截图识别到的内容、结论和未解决的问题。目标约 300 至 500 个中文字符，短通话可以更短。只输出一个自然段，不要标题或列表，不要添加对话里没有的事实。
"""

private const val VOICE_CALL_SUMMARY_MERGE_INSTRUCTIONS = """
以下是同一通语音通话按时间顺序生成的片段摘要。请合并为一个连贯的自然段，保留重要事实、决定、截图识别到的内容、结论和未解决的问题，删除重复信息。目标约 300 至 500 个中文字符，短通话可以更短。只输出一个自然段，不要标题或列表，不要添加对话里没有的事实。
"""

internal suspend fun ProviderManager.generateVoiceCallSummary(
    settings: Settings,
    messages: List<UIMessage>,
): String {
    val textGenerator = CompressionTextGenerator(this, settings)

    suspend fun generate(content: String, merge: Boolean): String {
        return textGenerator.generate(
            content = content,
            targetTokens = VOICE_CALL_SUMMARY_TARGET_TOKENS,
            additionalContext = if (merge) {
                VOICE_CALL_SUMMARY_MERGE_INSTRUCTIONS.trimIndent()
            } else {
                VOICE_CALL_SUMMARY_INSTRUCTIONS.trimIndent()
            },
        )
            ?: throw IllegalStateException("Failed to generate voice-call summary")
    }

    var summaries = splitMessagesForCompression(
        messages = messages.map { it.forConversationCompression(isVoiceCallTranscript = true) },
        targetTokens = VOICE_CALL_SUMMARY_TARGET_TOKENS,
    ).map { chunk ->
        generate(chunk.joinToString("\n\n") { it.summaryAsText() }, merge = false)
    }.filter { it.isNotBlank() }

    if (summaries.isEmpty()) throw IllegalStateException("Failed to generate voice-call summary")
    while (summaries.size > 1) {
        val mergedSummaries = splitTextsForCompression(summaries, VOICE_CALL_SUMMARY_TARGET_TOKENS)
            .map { group ->
                if (group.size == 1) group.single()
                else generate(group.joinToString("\n\n"), merge = true)
            }
            .filter { it.isNotBlank() }
        if (mergedSummaries.isEmpty() || mergedSummaries.size >= summaries.size) {
            throw IllegalStateException("Failed to merge voice-call summary")
        }
        summaries = mergedSummaries
    }

    return summaries.single()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
}
