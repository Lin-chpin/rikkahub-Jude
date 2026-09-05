package me.rerere.rikkahub.personal.heartbeat

/** A model reply is treated as one candidate; this layer validates novelty before delivery. */
data class HeartbeatThoughtDecision(
    val text: String,
    val score: Double,
    val novelty: Double,
    val pressure: Double,
) {
    val shouldDeliver: Boolean
        get() = text.isNotBlank() && novelty >= MIN_NOVELTY && score >= MIN_SCORE

    companion object {
        private const val MIN_NOVELTY = 0.20
        private const val MIN_SCORE = 0.35
    }
}

class HeartbeatDecisionEngine(
    private val desireState: HeartbeatDesireState,
    sentTexts: Iterable<String>,
) {
    private val recentSentTexts = sentTexts
        .map(String::trim)
        .filter(String::isNotBlank)
        .takeLast(20)

    fun evaluate(text: String): HeartbeatThoughtDecision {
        val normalizedText = normalize(text)
        val repetition = recentSentTexts
            .map { similarity(normalizedText, normalize(it)) }
            .maxOrNull()
            ?: 0.0
        val novelty = (1.0 - repetition).coerceIn(0.0, 1.0)
        val pressure = desireState.pressure()
        val score = (pressure * 0.55 + novelty * 0.45).coerceIn(0.0, 1.0)
        return HeartbeatThoughtDecision(
            text = text.trim(),
            score = score,
            novelty = novelty,
            pressure = pressure,
        )
    }

    private fun normalize(text: String): String = text
        .lowercase()
        .filter { !it.isWhitespace() && it.isLetterOrDigit() }

    private fun similarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        val leftNgrams = ngrams(left)
        val rightNgrams = ngrams(right)
        return leftNgrams.intersect(rightNgrams).size.toDouble() /
            leftNgrams.union(rightNgrams).size.coerceAtLeast(1)
    }

    private fun ngrams(text: String): Set<String> {
        if (text.length < 2) return setOf(text)
        return (0..text.length - 2).mapTo(mutableSetOf()) { index ->
            text.substring(index, index + 2)
        }
    }
}
