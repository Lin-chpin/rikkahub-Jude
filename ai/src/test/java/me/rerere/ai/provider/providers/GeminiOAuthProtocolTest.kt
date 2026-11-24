package me.rerere.ai.provider.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiOAuthProtocolTest {
    @Test
    fun `session id is stable for the same account conversation and uses Antigravity format`() {
        val first = deriveAntigravitySessionId("project-a", "conversation-a")
        val second = deriveAntigravitySessionId("project-a", "conversation-a")

        assertEquals(first, second)
        assertTrue(first.orEmpty().matches(Regex("-[0-9]+")))
    }

    @Test
    fun `session id differs across account projects and conversations`() {
        val original = deriveAntigravitySessionId("project-a", "conversation-a")

        assertNotEquals(original, deriveAntigravitySessionId("project-b", "conversation-a"))
        assertNotEquals(original, deriveAntigravitySessionId("project-a", "conversation-b"))
    }

    @Test
    fun `session id is added inside the native request and omitted without a conversation`() {
        val request = buildJsonObject { put("temperature", 0.2) }
        val withSession = request.withAntigravitySessionId("project-a", "conversation-a")
        val withoutSession = request.withAntigravitySessionId("project-a", null)

        assertTrue(withSession.getValue("sessionId").toString().matches(Regex("\"-[0-9]+\"")))
        assertNotSame(request, withSession)
        assertEquals(request, withoutSession)
    }
}
