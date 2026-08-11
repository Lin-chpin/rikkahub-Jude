package me.rerere.rikkahub.service

import me.rerere.rikkahub.utils.extractTtsQuotedContent
import me.rerere.rikkahub.utils.toChatTtsText
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextTransformTest {
    @Test
    fun extractsAsciiAndChineseQuotedSegmentsInSourceOrder() {
        assertEquals(
            listOf("第一段", "second", "第三段"),
            "前缀“第一段”，中间 \"second\"，最后『第三段』。".extractTtsQuotedContent(),
        )
    }

    @Test
    fun keepsApostrophesInsideWordsOutsideQuotedContent() {
        assertEquals(
            listOf("hello"),
            "don't say 'hello'".extractTtsQuotedContent(),
        )
    }

    @Test
    fun appliesQuoteAndEnglishFiltersForChatTts() {
        assertEquals(
            "hello",
            "说明“你好”并说 \"hello\"。".toChatTtsText(
                ttsOnlyReadQuoted = true,
                ttsEnglishOnly = true,
            ),
        )
    }
}
