package me.rerere.rikkahub.utils

private data class QuotePair(
    val opening: Char,
    val closing: Char,
)

private val ttsQuotePairs = listOf(
    QuotePair('"', '"'),
    QuotePair('\'', '\''),
    QuotePair('“', '”'),
    QuotePair('‘', '’'),
    QuotePair('「', '」'),
    QuotePair('『', '』'),
    QuotePair('《', '》'),
    QuotePair('〈', '〉'),
)

/** Extracts quoted segments while preserving their order in the source text. */
fun String.extractTtsQuotedContent(): List<String> {
    val result = mutableListOf<String>()
    var activePair: QuotePair? = null
    var contentStart = -1

    for (index in indices) {
        val character = this[index]
        val pair = activePair
        if (pair == null) {
            val openingPair = ttsQuotePairs.firstOrNull { it.opening == character }
            if (openingPair != null && !isApostropheInsideWord(index, character)) {
                activePair = openingPair
                contentStart = index + 1
            }
        } else if (character == pair.closing) {
            substring(contentStart, index).takeIf { it.isNotBlank() }?.let(result::add)
            activePair = null
            contentStart = -1
        }
    }

    return result
}

fun String.extractTtsQuotedContentAsText(separator: String = "\n"): String? {
    return extractTtsQuotedContent().takeIf { it.isNotEmpty() }?.joinToString(separator)
}

fun String.toChatTtsText(
    ttsOnlyReadQuoted: Boolean,
    ttsEnglishOnly: Boolean,
): String {
    val sourceText = if (ttsOnlyReadQuoted) {
        extractTtsQuotedContentAsText() ?: this
    } else {
        this
    }
    val plainText = sourceText.stripMarkdown()
    return if (ttsEnglishOnly) {
        plainText.keepEnglishOnlyForTts()
    } else {
        plainText
    }.trim()
}

private fun String.isApostropheInsideWord(index: Int, character: Char): Boolean {
    if (character != '\'') return false
    return index > 0 && index < lastIndex && this[index - 1].isLetter() && this[index + 1].isLetter()
}
