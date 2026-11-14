package io.github.maximums.web

import arrow.core.right
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

private const val SEARCH_RESPONSE = """
{
  "query": "koog agents",
  "answer": null,
  "images": [],
  "results": [
    {"title": "Koog", "url": "https://koog.ai", "content": "Koog is a Kotlin agent framework.", "score": 0.91, "raw_content": "# Koog\n\nKoog is a Kotlin agent framework for building AI agents.", "published_date": "Tue, 11 Mar 2025 17:00:00 GMT", "favicon": "https://koog.ai/favicon.ico"},
    {"title": "Koog on GitHub", "url": "https://github.com/JetBrains/koog", "content": "Source code.", "score": 0.84, "raw_content": null, "published_date": null}
  ],
  "response_time": 1.2,
  "request_id": "abc-123"
}
"""

private val QUERY = SearchQuery("koog agents")

val TavilySearchProviderTest by testSuite {
    fun searchTavily(reply: TavilyReply = TavilyReply(body = SEARCH_RESPONSE)) = RecordingTavily(reply)

    fun RecordingTavily.provider() = TavilySearchProvider("tvly-test", http)

    suspend fun failedSearch(reply: TavilyReply): ApiError {
        val tavily = searchTavily(reply)
        val error = assertNotNull(tavily.provider().search(QUERY, limit = 5).leftOrNull(), "expected an API error")
        assertEquals(1, tavily.requests.size, "the provider itself never retries")
        return error
    }

    test("maps results in Tavily's order, with page text and publication date when Tavily has them") {
        val results = searchTavily().provider().search(QUERY, limit = 5)

        assertEquals(
            listOf(
                SearchResult(
                    "https://koog.ai", "Koog", "Koog is a Kotlin agent framework.", rank = 0,
                    content = "# Koog\n\nKoog is a Kotlin agent framework for building AI agents.",
                    publishedDate = LocalDate.of(2025, 3, 11),
                ),
                SearchResult("https://github.com/JetBrains/koog", "Koog on GitHub", "Source code.", rank = 1),
            ).right(),
            results,
        )
    }

    test("sends the query with bearer auth") {
        val tavily = searchTavily()
        tavily.provider().search(QUERY, limit = 5)

        val request = tavily.requests.single()
        assertEquals("https://api.tavily.com/search", request.url.toString())
        assertEquals("Bearer tvly-test", request.headers[HttpHeaders.Authorization])
        assertEquals("koog agents", tavily.sentJson()["query"]?.jsonPrimitive?.content)
        assertEquals(5, tavily.sentJson()["max_results"]?.jsonPrimitive?.int)
        assertEquals("basic", tavily.sentJson()["search_depth"]?.jsonPrimitive?.content)
        assertEquals(2, tavily.sentJson()["chunks_per_source"]?.jsonPrimitive?.int)
        assertEquals("markdown", tavily.sentJson()["include_raw_content"]?.jsonPrimitive?.content)
        assertEquals(true, tavily.sentJson()["include_published_date"]?.jsonPrimitive?.boolean)
        assertEquals(JsonNull, tavily.sentJson()["time_range"], "no time range unless the query asks for one")
    }

    test("sends a query's recency as Tavily's time range") {
        val expected = mapOf(Recency.DAY to "day", Recency.WEEK to "week", Recency.MONTH to "month", Recency.YEAR to "year")
        for ((recency, timeRange) in expected) {
            val tavily = searchTavily()
            tavily.provider().search(SearchQuery("koog agents", recency), limit = 5)

            assertEquals(timeRange, tavily.sentJson()["time_range"]?.jsonPrimitive?.content)
        }
    }

    test("treats a publication date it can't read as unknown") {
        val tavily = searchTavily(
            TavilyReply(body = """{"results": [{"title": "Koog", "url": "https://koog.ai", "content": "Snippet.", "published_date": "sometime last year"}]}""")
        )

        val results = assertNotNull(tavily.provider().search(QUERY, limit = 5).getOrNull())
        assertNull(results.single().publishedDate)
    }

    test("treats blank page text as missing") {
        val tavily = searchTavily(
            TavilyReply(body = """{"results": [{"title": "Empty", "url": "https://example.com", "content": "Snippet.", "raw_content": "  "}]}""")
        )

        val results = assertNotNull(tavily.provider().search(QUERY, limit = 5).getOrNull())
        assertNull(results.single().content)
    }

    test("caps max_results at Tavily's limit") {
        val tavily = searchTavily()
        tavily.provider().search(QUERY, limit = 50)

        assertEquals(20, tavily.sentJson()["max_results"]?.jsonPrimitive?.int)
    }

    test("turns Tavily's error statuses into API errors, keeping Tavily's message") {
        val expected = mapOf(
            400 to ApiError.InvalidRequest::class,
            401 to ApiError.Unauthorized::class,
            404 to ApiError.Unexpected::class,
            422 to ApiError.InvalidRequest::class,
            429 to ApiError.RateLimited::class,
            432 to ApiError.QuotaExceeded::class,
            433 to ApiError.QuotaExceeded::class,
            500 to ApiError.Unavailable::class,
            503 to ApiError.Unavailable::class,
        )
        for ((status, type) in expected) {
            val error = failedSearch(TavilyReply(HttpStatusCode.fromValue(status), TAVILY_ERROR_BODY))

            assertEquals(type, error::class, "HTTP $status")
            assertContains(error.message, "HTTP $status")
            assertContains(error.message, "Tavily says no.")
        }
    }

    test("reads how long a rate limit asks to wait") {
        val withHeader = failedSearch(TavilyReply(HttpStatusCode.TooManyRequests, TAVILY_ERROR_BODY, retryAfter = "3"))
        val withoutHeader = failedSearch(TavilyReply(HttpStatusCode.TooManyRequests, TAVILY_ERROR_BODY))

        assertEquals(3.seconds, assertIs<ApiError.RateLimited>(withHeader).retryAfter)
        assertNull(assertIs<ApiError.RateLimited>(withoutHeader).retryAfter)
    }

    test("reports Tavily as unavailable when it can't be reached") {
        val unreachable = HttpClient(MockEngine { throw IOException("Connection refused") }) {
            install(ContentNegotiation) { json() }
        }

        val error = TavilySearchProvider("tvly-test", unreachable).search(QUERY, limit = 5).leftOrNull()

        assertIs<IOException>(assertIs<ApiError.Unavailable>(error).cause)
    }

    test("reports a response that can't be read as unexpected") {
        val error = failedSearch(TavilyReply(body = "<html>Bad gateway</html>"))

        assertIs<ApiError.Unexpected>(error)
    }

    test("rejects a blank API key or a chunk count outside 1 to 3 up front") {
        val http = searchTavily().http
        assertFailsWith<IllegalArgumentException> { TavilySearchProvider(" ", http) }
        assertFailsWith<IllegalArgumentException> { TavilySearchProvider("tvly-test", http, chunksPerSource = 0) }
        assertFailsWith<IllegalArgumentException> { TavilySearchProvider("tvly-test", http, chunksPerSource = 4) }
    }
}
