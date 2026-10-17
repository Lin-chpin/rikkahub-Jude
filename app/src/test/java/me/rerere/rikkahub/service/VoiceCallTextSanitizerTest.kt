package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallTextSanitizerTest {
    @Test
    fun removesParenthesizedKaomojiButKeepsMiniMaxAudioTags() {
        assertEquals(
            "(laughs) 你好。 (sighs) 我没事。",
            "(laughs) 你好（￣▽￣）。 (sighs) 我没事。".sanitizeVoiceCallTextForSpeech(),
        )
    }

    @Test
    fun removesComplexParenthesizedKaomojiWithoutRemovingNormalParentheses() {
        assertEquals(
            "这是 (测试) 内容。",
            "这是 (测试) 内容。(╯°□°）╯︵ ┻━┻".sanitizeVoiceCallTextForSpeech(),
        )
    }

    @Test
    fun quoteOnlyModeFiltersCallSpeechAndDefersUnquotedFallbackUntilFinished() {
        assertEquals(
            "我是",
            "\"我是\"（你好）".sanitizeVoiceCallTextForSpeech(ttsOnlyReadQuoted = true),
        )
        assertEquals(
            "",
            "\"我是（你好）".sanitizeVoiceCallTextForSpeech(
                ttsOnlyReadQuoted = true,
                allowUnquotedFallback = false,
            ),
        )
        assertEquals(
            "没有引号的回复",
            "没有引号的回复".sanitizeVoiceCallTextForSpeech(ttsOnlyReadQuoted = true),
        )
        assertEquals(
            "",
            "前文“尚未闭合的引号".sanitizeVoiceCallTextForSpeech(
                ttsOnlyReadQuoted = true,
                allowUnquotedFallback = true,
            ),
        )
        assertEquals(
            "It's fine",
            "It's fine".sanitizeVoiceCallTextForSpeech(ttsOnlyReadQuoted = true),
        )
    }

    @Test
    fun realtimeFullDuplexReadsWholeReplyEvenWhenQuoteOnlyIsEnabled() {
        val reply = "待って。「はい、聞こえるよ」返事のあとも話す。"

        assertEquals(
            reply,
            reply.sanitizeRealtimeVoiceCallTextForSpeech(
                ttsOnlyReadQuoted = true,
                fullDuplex = true,
                allowUnquotedFallback = true,
            ),
        )
        assertEquals(
            "はい、聞こえるよ",
            reply.sanitizeRealtimeVoiceCallTextForSpeech(
                ttsOnlyReadQuoted = true,
                fullDuplex = false,
                allowUnquotedFallback = true,
            ),
        )
    }

    @Test
    fun realtimeSpeechIgnoresWhitespaceOnlyPrefixUntilActualTextArrives() {
        val emptyPrefix = "\n".sanitizeRealtimeVoiceCallTextForSpeech(
            ttsOnlyReadQuoted = false,
            fullDuplex = true,
            allowUnquotedFallback = false,
        )
        val speech = "続き。".sanitizeRealtimeVoiceCallTextForSpeech(
            ttsOnlyReadQuoted = false,
            fullDuplex = true,
            allowUnquotedFallback = false,
        )

        assertEquals("", emptyPrefix)
        assertTrue(speech.startsWith(emptyPrefix))
        assertEquals("続き。", speech)
    }
}
