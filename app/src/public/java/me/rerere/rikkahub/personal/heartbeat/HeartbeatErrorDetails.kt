package me.rerere.rikkahub.personal.heartbeat

/**
 * Keeps the complete exception cause chain in the private heartbeat diagnostics.
 * Android's foreground-service exceptions often put the useful reason in the
 * exception message or a nested cause rather than in the top-level class name.
 */
fun Throwable.toHeartbeatDiagnosticDetail(stage: String): String {
    val seen = mutableSetOf<Throwable>()
    val causes = buildList {
        var current: Throwable? = this@toHeartbeatDiagnosticDetail
        while (current != null && seen.add(current)) {
            val throwable = current
            add(
                buildString {
                    append(throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable")
                    append(": ")
                    append(throwable.message ?: "<no message>")
                },
            )
            current = throwable.cause
        }
    }
    return "stage=$stage causeChain=${causes.joinToString(" <- ")}"
}
