package me.rerere.common.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseHttpErrorDetailsTest {
    @Test
    fun extractsAllowlistedProviderFieldsWithoutPreservingFreeText() {
        assertEquals(
            SseHttpErrorDetails("payment_issue", "authentication_error"),
            parseSseHttpErrorDetails(
                """{"error":{"code":"payment_issue","type":"authentication_error","message":"private body"}}""",
            ),
        )
        assertNull(parseSseHttpErrorDetails("""{"error":{"code":"token=secret"}}""").code)
        assertNull(parseSseHttpErrorDetails("not json").code)
    }
}
