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

    @Test
    fun `parses distinct 5h and weekly buckets within the same Gemini group`() {
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
                        "bucketId": "gemini-5h",
                        "displayName": "Gemini",
                        "window": "5h",
                        "remaining": {
                          "remainingFraction": 0.42,
                          "resetTime": "2026-11-24T05:00:00Z"
                        }
                      },
                      {
                        "bucketId": "gemini-weekly",
                        "displayName": "Gemini",
                        "window": "weekly",
                        "remaining": {
                          "remainingFraction": 0.88,
                          "resetTime": "2026-11-30T00:00:00Z"
                        }
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        ).jsonObject

        val buckets = parseGeminiOAuthQuota(summary).single().buckets

        assertEquals(listOf("gemini-5h", "gemini-weekly"), buckets.map { it.id })
        assertEquals(listOf("5h", "weekly"), buckets.map { it.window })
        assertEquals(0.42, buckets[0].remainingFraction!!, 0.0001)
        assertEquals(0.88, buckets[1].remainingFraction!!, 0.0001)
        assertEquals("2026-11-24T05:00:00Z", buckets[0].resetTime)
        assertEquals("2026-11-30T00:00:00Z", buckets[1].resetTime)
    }

    @Test
    fun `expands windows array and named window sub-objects into separate buckets`() {
        val summary = Json.parseToJsonElement(
            """
            {
              "quotaGroups": [
                {
                  "groupId": "gemini",
                  "displayName": "Gemini",
                  "quotaBuckets": [
                    {
                      "bucketId": "gemini-pro",
                      "displayName": "Gemini Pro",
                      "windows": [
                        {
                          "window": "QUOTA_WINDOW_5H",
                          "remainingFraction": 0.35,
                          "resetTime": "2026-11-24T05:00:00Z"
                        },
                        {
                          "window": "QUOTA_WINDOW_WEEKLY",
                          "remainingFraction": 0.79,
                          "resetTime": "2026-11-30T00:00:00Z"
                        }
                      ]
                    },
                    {
                      "bucketId": "gemini-flash",
                      "displayName": "Gemini Flash",
                      "fiveHour": {
                        "remainingFraction": 0.60,
                        "resetTime": "2026-11-24T06:00:00Z"
                      },
                      "weekly": {
                        "remainingFraction": 0.95,
                        "resetTime": "2026-11-30T01:00:00Z"
                      }
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val buckets = parseGeminiOAuthQuota(summary).single().buckets

        assertEquals(4, buckets.size)
        assertEquals(listOf("5h", "weekly", "5h", "weekly"), buckets.map { it.window })
        assertEquals(0.35, buckets[0].remainingFraction!!, 0.0001)
        assertEquals(0.79, buckets[1].remainingFraction!!, 0.0001)
        assertEquals(0.60, buckets[2].remainingFraction!!, 0.0001)
        assertEquals(0.95, buckets[3].remainingFraction!!, 0.0001)
    }

    @Test
    fun `parses top-level buckets and infers 5h vs weekly window from bucketId without model catalog fallback`() {
        val summary = Json.parseToJsonElement(
            """
            {
              "buckets": [
                {
                  "bucketId": "gemini-5h",
                  "displayName": "Gemini",
                  "remainingFraction": 0.25,
                  "resetTime": "2026-11-24T05:00:00Z"
                },
                {
                  "bucketId": "gemini-argon-wtu-weekly",
                  "displayName": "Gemini Argon",
                  "remainingFraction": 0.91,
                  "resetTime": "2026-11-30T00:00:00Z"
                },
                {
                  "bucketId": "3p-5h",
                  "displayName": "Claude & GPT",
                  "remainingFraction": 1.0
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        val catalog = Json.parseToJsonElement(
            """
            {
              "models": {
                "gemini-3-flash": {
                  "displayName": "Gemini 3 Flash",
                  "quotaInfo": {"remainingFraction": 1.0, "resetTime": "2026-11-24T00:00:00Z"}
                }
              }
            }
            """.trimIndent()
        ).jsonObject

        val groups = parseGeminiOAuthQuota(summary, catalog)

        assertEquals(1, groups.size)
        val buckets = groups.single().buckets
        assertEquals(listOf("gemini-5h", "gemini-argon-wtu-weekly"), buckets.map { it.id })
        assertEquals(listOf("5h", "weekly"), buckets.map { it.window })
        assertEquals(0.25, buckets[0].remainingFraction!!, 0.0001)
        assertEquals(0.91, buckets[1].remainingFraction!!, 0.0001)
    }

    @Test
    fun `parses Gemini buckets under opaque group ids and normalizes 5hr and seven-day suffixes`() {
        val summary = Json.parseToJsonElement(
            """
            {
              "groups": [
                {
                  "groupId": "default",
                  "displayName": "Default Pool",
                  "buckets": [
                    {
                      "bucketId": "gemini-5hr",
                      "displayName": "Gemini",
                      "remainingFraction": 0.4
                    },
                    {
                      "bucketId": "gemini-seven-day",
                      "displayName": "Gemini",
                      "remainingFraction": 0.85
                    },
                    {
                      "bucketId": "claude-5hr",
                      "displayName": "Claude",
                      "remainingFraction": 0.5
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val groups = parseGeminiOAuthQuota(summary)

        assertEquals(1, groups.size)
        val buckets = groups.single().buckets
        assertEquals(listOf("gemini-5hr", "gemini-seven-day"), buckets.map { it.id })
        assertEquals(listOf("5h", "7d"), buckets.map { it.window })
    }
}
