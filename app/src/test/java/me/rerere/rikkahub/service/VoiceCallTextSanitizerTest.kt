package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
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
}
