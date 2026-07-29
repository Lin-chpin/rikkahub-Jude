package me.rerere.rikkahub.service

internal fun String.sanitizeVoiceCallTextForSpeech(): String {
    if (isBlank()) return this

    var text = removeVoiceCallEmojiCodePoints()
        .replace(VOICE_CALL_MARKDOWN_IMAGE_REGEX, "")
        .replace(VOICE_CALL_CODE_BLOCK_REGEX, "")
        .replace(VOICE_CALL_INLINE_CODE_REGEX, "")
        .replace(VOICE_CALL_JSONISH_LINE_REGEX, "")
        .replace(VOICE_CALL_CONTROL_CHARS_REGEX, "")
        .replace('\uFFFD', ' ')
        .replace(VOICE_CALL_KAOMOJI_REGEX, "")
        .replace(VOICE_CALL_WIDE_KAOMOJI_REGEX, "")
        .replace(VOICE_CALL_ASCII_EMOTICON_REGEX, "")
        .replace(VOICE_CALL_TEXT_FACE_REGEX, "")
        .removeVoiceCallParenthesizedKaomoji()
        .removeLikelyMojibakeTail()
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex(" *\\n{3,} *"), "\n\n")

    repeat(2) {
        text = text
            .replace(VOICE_CALL_KAOMOJI_REGEX, "")
            .replace(VOICE_CALL_WIDE_KAOMOJI_REGEX, "")
            .replace(VOICE_CALL_ASCII_EMOTICON_REGEX, "")
            .replace(VOICE_CALL_TEXT_FACE_REGEX, "")
            .removeVoiceCallParenthesizedKaomoji()
    }

    return text.trim()
}

internal fun String.sanitizeVoiceCallTextForOutput(): String = sanitizeVoiceCallTextForSpeech()

private fun String.removeVoiceCallEmojiCodePoints(): String {
    val output = StringBuilder(length)
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        if (!codePoint.isVoiceCallEmojiCodePoint()) {
            output.appendCodePoint(codePoint)
        }
        index += Character.charCount(codePoint)
    }
    return output.toString()
}

private fun Int.isVoiceCallEmojiCodePoint(): Boolean {
    return this in 0x1F000..0x1FAFF ||
        this in 0x2600..0x27BF ||
        this in 0x2300..0x23FF ||
        this in 0x2B00..0x2BFF ||
        this in 0xFE00..0xFE0F ||
        this in 0x1F1E6..0x1F1FF ||
        this in 0x1F3FB..0x1F3FF ||
        this == 0x00A9 ||
        this == 0x00AE ||
        this == 0x200D ||
        this == 0x203C ||
        this == 0x2049 ||
        this == 0x20E3 ||
        this == 0x2122 ||
        this == 0x2139 ||
        this == 0x3030 ||
        this == 0x303D ||
        this == 0x3297 ||
        this == 0x3299
}

private val VOICE_CALL_MARKDOWN_IMAGE_REGEX = Regex("""!\[[^\]]*]\([^)]*\)""")
private val VOICE_CALL_CODE_BLOCK_REGEX = Regex("""(?s)```.*?```""")
private val VOICE_CALL_INLINE_CODE_REGEX = Regex("""`[^`\n]{1,120}`""")
private val VOICE_CALL_JSONISH_LINE_REGEX = Regex("""(?m)^\s*["{,}\]]?[\w.-]{1,40}["']?\s*[:=]\s*["']?.*$""")
private val VOICE_CALL_CONTROL_CHARS_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")
private val VOICE_CALL_KAOMOJI_REGEX = Regex("""[（(【\[{<][^）)】\]}\n]{0,24}[ω∀▽Дд益へ_・ー^><=≧≦；;｀´﹏﹋ノヽっつTдД][^）)】\]}\n]{0,24}[）)】\]}>]""")
private val VOICE_CALL_WIDE_KAOMOJI_REGEX = Regex("""(?<![\p{L}\p{N}])[\^TQxX;；=≧≦><][_\-oO.。﹏~～^TQxX;；=≧≦><]{1,8}[\^TQxX;；=≧≦><](?![\p{L}\p{N}])""")
private val VOICE_CALL_ASCII_EMOTICON_REGEX = Regex("""(?:(?<=^)|(?<=\s))[:;=8xX][\-o*']?[\)\]\(\[dDpP/:\}{@|\\](?=\s|$|[，。！？,.!?])""")
private val VOICE_CALL_TEXT_FACE_REGEX = Regex("""(?:(?<=^)|(?<=[\s，。！？,.!?]))(?:QAQ|QwQ|qwq|TAT|T_T|T-T|OTZ|orz|2333*)(?=$|[\s，。！？,.!?])""")
private val VOICE_CALL_PARENTHESIZED_TOKEN_REGEX = Regex("""[（(][^）)\r\n]{1,80}[）)](?:[^\p{L}\p{N}\p{P}\r\n]{1,24})?""")
private val VOICE_CALL_KAOMOJI_SYMBOL_TAIL_REGEX = Regex("""(?<![\p{L}\p{N}])[^\p{L}\p{N}\r\n]{1,24}(?![\p{L}\p{N}])""")

private fun String.removeVoiceCallParenthesizedKaomoji(): String {
    return replace(VOICE_CALL_PARENTHESIZED_TOKEN_REGEX) { match ->
        val content = match.value.drop(1).dropLast(1)
        val hasCjkText = content.any { character ->
            when (Character.UnicodeScript.of(character.code)) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
                    -> true

                else -> false
            }
        }
        val letterCount = content.count { it.isLetter() }
        val hasKaomojiMarker = content.any(Char::isVoiceCallKaomojiMarker)
        if (!hasCjkText && letterCount <= 3 && hasKaomojiMarker) "" else match.value
    }.replace(VOICE_CALL_KAOMOJI_SYMBOL_TAIL_REGEX) { match ->
        if (match.value.any(Char::isVoiceCallKaomojiMarker)) {
            match.value.filter { it.isWhitespace() || it in "。！？!?，,；;：:、." }
        } else {
            match.value
        }
    }
}

private fun Char.isVoiceCallKaomojiMarker(): Boolean {
    return this in "_^~°▽Дд益ωಠ╯╰┻︵￣＿﹏﹋" ||
        Character.getType(this) == Character.OTHER_SYMBOL.toInt()
}

private fun String.removeLikelyMojibakeTail(): String {
    val suspiciousStart = indexOfFirst { it == '�' || it.code in 0x0080..0x009F }
    return if (suspiciousStart >= 0 && suspiciousStart >= length / 2) {
        substring(0, suspiciousStart)
    } else {
        this
    }
}
