package me.rerere.search

import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderResponseParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SearchHttpFailureTest {
    @Test
    fun httpStatusSurvivesSearchServiceFailureWithoutForwardingResponseBody() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://search.example/api").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .body("""{"error":{"code":"rate_limit_exceeded","type":"rate_limit_error","message":"secret provider body"}}""".toResponseBody())
            .build()

        try {
            response.throwSearchHttpFailure("Firecrawl search")
            fail("Expected typed HTTP error")
        } catch (error: HttpException) {
            assertEquals(429, error.statusCode)
            assertEquals("Firecrawl search request failed", error.message)
            assertEquals("rate_limit_exceeded", error.providerCode)
            assertEquals("rate_limit_error", error.providerType)
            assertTrue(error.message.orEmpty().contains("secret provider body").not())
        }
    }

    @Test
    fun malformedSearchResponseKeepsItsParsingCauseInATypedFailure() {
        val failure = runCatching {
            parseSearchResponse<Int> { throw IllegalArgumentException("provider body secret") }
        }.exceptionOrNull()

        assertTrue(failure is ProviderResponseParseException)
        assertEquals("provider body secret", failure?.cause?.message)
        assertTrue(failure?.message?.contains("provider body secret") == false)
    }

    @Test
    fun missingProviderResponseFieldIsClassifiedAsFormatFailure() {
        val failure = runCatching { missingSearchResponseField("data") }.exceptionOrNull()

        assertTrue(failure is ProviderResponseParseException)
        assertEquals("Missing required response field: data", failure?.cause?.message)
    }

    @Test
    fun missingSearchApiKeyHasConfigurationProvenance() {
        val failure = runCatching { missingSearchApiKey() }.exceptionOrNull() as? HttpException

        assertEquals("configuration", failure?.providerType)
        assertEquals("missing_api_key", failure?.providerCode)
    }

    @Test
    fun grokMissingApiKeyReturnsConfigurationFailure() = runBlocking {
        val result = GrokSearchService.search(
            Json.parseToJsonElement("{\"query\":\"test\"}").jsonObject,
            SearchCommonOptions(),
            SearchServiceOptions.GrokOptions(apiKey = ""),
        )
        val failure = result.exceptionOrNull() as? HttpException

        assertEquals("configuration", failure?.providerType)
        assertEquals("missing_api_key", failure?.providerCode)
    }

    @Test
    fun perplexityMissingApiKeyReturnsConfigurationFailure() = runBlocking {
        val result = PerplexitySearchService.search(
            Json.parseToJsonElement("{\"query\":\"test\"}").jsonObject,
            SearchCommonOptions(),
            SearchServiceOptions.PerplexityOptions(apiKey = ""),
        )
        val failure = result.exceptionOrNull() as? HttpException

        assertEquals("configuration", failure?.providerType)
        assertEquals("missing_api_key", failure?.providerCode)
    }

    @Test
    fun configuredSearchAdaptersRejectMissingApiKeysBeforeSendingRequests() = runBlocking {
        val query = Json.parseToJsonElement("""{"query":"test"}""").jsonObject
        val url = Json.parseToJsonElement("""{"url":"https://example.com"}""").jsonObject
        val searches = listOf(
            "Brave" to BraveSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.BraveOptions()),
            "Bocha" to BochaSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.BochaOptions()),
            "Exa" to ExaSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.ExaOptions()),
            "Firecrawl" to FirecrawlSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.FirecrawlOptions()),
            "Jina" to JinaSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.JinaOptions()),
            "LinkUp" to LinkUpService.search(query, SearchCommonOptions(), SearchServiceOptions.LinkUpOptions()),
            "Metaso" to MetasoSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.MetasoOptions()),
            "Ollama" to OllamaSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.OllamaOptions()),
            "RikkaHub" to RikkaHubSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.RikkaHubOptions()),
            "Tavily" to TavilySearchService.search(query, SearchCommonOptions(), SearchServiceOptions.TavilyOptions()),
            "Tinyfish" to TinyfishSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.TinyfishOptions()),
            "Zhipu" to ZhipuSearchService.search(query, SearchCommonOptions(), SearchServiceOptions.ZhipuOptions()),
        )
        val scrapes = listOf(
            "Firecrawl" to FirecrawlSearchService.scrape(url, SearchCommonOptions(), SearchServiceOptions.FirecrawlOptions()),
            "Jina" to JinaSearchService.scrape(url, SearchCommonOptions(), SearchServiceOptions.JinaOptions()),
            "LinkUp" to LinkUpService.scrape(url, SearchCommonOptions(), SearchServiceOptions.LinkUpOptions()),
            "Tavily" to TavilySearchService.scrape(url, SearchCommonOptions(), SearchServiceOptions.TavilyOptions()),
            "Tinyfish" to TinyfishSearchService.scrape(url, SearchCommonOptions(), SearchServiceOptions.TinyfishOptions()),
        )

        (searches + scrapes).forEach { (service, result) ->
            val failure = result.exceptionOrNull() as? HttpException
            assertEquals("$service failure scope", "configuration", failure?.providerType)
            assertEquals("$service failure code", "missing_api_key", failure?.providerCode)
        }
    }

    @Test
    fun malformedSearchOptionsAreReportedAsArgumentFailures() = runBlocking {
        val invalidTavilyTopicType = TavilySearchService.search(
            Json.parseToJsonElement("""{"query":"test","topic":4}""").jsonObject,
            SearchCommonOptions(),
            SearchServiceOptions.TavilyOptions(apiKey = "configured"),
        ).exceptionOrNull()
        val unsupportedTavilyTopic = TavilySearchService.search(
            Json.parseToJsonElement("""{"query":"test","topic":"images"}""").jsonObject,
            SearchCommonOptions(),
            SearchServiceOptions.TavilyOptions(apiKey = "configured"),
        ).exceptionOrNull()
        val invalidFirecrawlSourcesType = FirecrawlSearchService.search(
            Json.parseToJsonElement("""{"query":"test","sources":5}""").jsonObject,
            SearchCommonOptions(),
            SearchServiceOptions.FirecrawlOptions(apiKey = "configured"),
        ).exceptionOrNull()
        val invalidFirecrawlCategoryShape = FirecrawlSearchService.search(
            Json.parseToJsonElement("""{"query":"test","categories":["research",null]}""").jsonObject,
            SearchCommonOptions(),
            SearchServiceOptions.FirecrawlOptions(apiKey = "configured"),
        ).exceptionOrNull()
        val unsupportedFirecrawlSource = FirecrawlSearchService.search(
            Json.parseToJsonElement("""{"query":"test","sources":["images"]}""").jsonObject,
            SearchCommonOptions(),
            SearchServiceOptions.FirecrawlOptions(apiKey = "configured"),
        ).exceptionOrNull()
        val unsupportedFirecrawlCategory = FirecrawlSearchService.search(
            Json.parseToJsonElement("""{"query":"test","categories":["videos"]}""").jsonObject,
            SearchCommonOptions(),
            SearchServiceOptions.FirecrawlOptions(apiKey = "configured"),
        ).exceptionOrNull()

        listOf(
            invalidTavilyTopicType,
            unsupportedTavilyTopic,
            invalidFirecrawlSourcesType,
            invalidFirecrawlCategoryShape,
            unsupportedFirecrawlSource,
            unsupportedFirecrawlCategory,
        ).forEach { assertTrue(it is SearchToolArgumentException) }
    }

    @Test
    fun providerReportedFailureRetainsOnlySafeCode() {
        val failure = runCatching { searchProviderReportedFailure("1001") }.exceptionOrNull() as? HttpException
        val unsafeFailure = runCatching { searchProviderReportedFailure("secret provider message") }
            .exceptionOrNull() as? HttpException

        assertEquals("remote_service", failure?.providerType)
        assertEquals("1001", failure?.providerCode)
        assertEquals(null, failure?.statusCode)
        assertEquals(null, unsafeFailure?.providerCode)
        assertTrue(unsafeFailure?.message?.contains("secret provider message") == false)
    }

    @Test
    fun requiredSearchArgumentsHaveTheirOwnFailureType() {
        val missing = runCatching {
            Json.parseToJsonElement("{}").jsonObject.requireSearchStringArgument("query")
        }.exceptionOrNull()
        val invalidType = runCatching {
            Json.parseToJsonElement("{\"query\": 5}").jsonObject.requireSearchStringArgument("query")
        }.exceptionOrNull()

        assertTrue(missing is SearchToolArgumentException)
        assertTrue(invalidType is SearchToolArgumentException)
    }
}
