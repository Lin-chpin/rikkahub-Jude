package me.rerere.rikkahub.personal.heartbeat

/** Only window ownership is kept; screen text and titles never enter this check. */
data class SentinelScreenSnapshot(val revision: Long, val windows: Map<Int, String?>) {
    fun blockReason(blockedPackages: Set<String>): String? = when {
        blockedPackages.isEmpty() -> null
        windows.values.any { it != null && it in blockedPackages } -> "privacy_blocked"
        windows.isEmpty() || windows.values.any { it.isNullOrBlank() } -> "screen_app_unknown"
        else -> null
    }

    fun changedSince(before: SentinelScreenSnapshot) = revision != before.revision || windows != before.windows
}

const val SENTINEL_PRIVACY_MESSAGE = "此处为敏感隐私内容，禁止截图"
