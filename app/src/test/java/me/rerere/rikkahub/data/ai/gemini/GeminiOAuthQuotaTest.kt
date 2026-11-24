package me.rerere.rikkahub.data.ai.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiOAuthQuotaTest {
    @Test
    fun `parses Gemini quota buckets and ignores other provider groups`() {
        val summary = Json.parseToJsonElement(
            """
            {
              "response": {
                "groups": [
                  {
                    "groupId": "gemini",
                    "displayName": "Gemini",
                    "buckets": [
                      {
                        "bucketId": "gemini-3-flash",
                        "displayName": "Gemini 3 Flash",
                        "window": "5h",
                        "remaining": {
                          "remainingFraction": 0.42,
                          "resetTime": "2026-11-23T23:00:00Z"
                        }
                      }
                    ]
                  },
                  {
                    "groupId": "claude",
                    "displayName": "Claude",
                    "buckets": [{"bucketId":"other", "remainingFraction":0.9}]
                  }
                ]
              }
            }
            """.trimIndent()
        ).jsonObject

        val groups = parseGeminiOAuthQuota(summary)

        assertEquals(1, groups.size)
        assertEquals("gemini", groups.single().id)
        assertEquals("Gemini 3 Flash", groups.single().buckets.single().displayName)
        assertEquals(0.42, groups.single().buckets.single().remainingFraction!!, 0.0001)
        assertEquals("2026-11-23T23:00:00Z", groups.single().buckets.single().resetTime)
    }

    @Test
    fun `uses model quota fallback without displaying missing values as zero`() {
        val summary = Json.parseToJsonElement("{} ").jsonObject
        val catalog = Json.parseToJsonElement(
            """
            {
              "models": {
                "gemini-3-flash": {
                  "displayName": "Gemini 3 Flash",
                  "quotaInfo": {"remainingFraction":0.6, "resetTime":"2026-11-24T00:00:00Z"}
                },
                "gemini-internal": {
                  "isInternal": true,
                  "quotaInfo": {"remainingFraction":1.0}
                },
                "gemini-no-quota": {"displayName":"Gemini without quota"}
              }
            }
            """.trimIndent()
        ).jsonObject

        val groups = parseGeminiOAuthQuota(summary, catalog)

        assertEquals(1, groups.size)
        assertEquals(listOf("gemini-3-flash"), groups.single().buckets.map { it.id })
        assertNull(groups.single().buckets.single().window)
        assertEquals(0.6, groups.single().buckets.single().remainingFraction!!, 0.0001)
    }

    @Test
    fun `does not infer zero when the provider omits quota fields`() {
        val summary = Json.parseToJsonElement(
            """{"groups":[{"groupId":"gemini","displayName":"Gemini","buckets":[{"bucketId":"flash"}]}]}"""
        ).jsonObject

        assertTrue(parseGeminiOAuthQuota(summary).isEmpty())
    }
}
