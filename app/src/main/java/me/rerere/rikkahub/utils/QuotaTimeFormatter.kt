package me.rerere.rikkahub.utils

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
private val QUOTA_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

internal fun formatQuotaResetTimeInShanghai(value: String): String {
    val normalized = value.trim()
    val instant = normalized.toLongOrNull()?.let { epoch ->
        runCatching {
            if (epoch > 100_000_000_000L) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch)
        }.getOrNull()
    } ?: runCatching { Instant.parse(normalized) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(normalized).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: return normalized
    return QUOTA_TIME_FORMAT.withZone(SHANGHAI_ZONE).format(instant)
}
