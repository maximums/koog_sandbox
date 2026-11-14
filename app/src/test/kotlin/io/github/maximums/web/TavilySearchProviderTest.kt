package io.github.maximums.web

import arrow.core.right
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
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

private const val TAVILY_RESPONSE = """
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

private const val ERROR_BODY = """{"detail":{"error":"Tavily says no."}}"""

private data class TavilyReply(
    val status: HttpStatusCode = HttpStatusCode.OK,
    val body: String = TAVILY_RESPONSE,
    val retryAfter: String? = null,
)

val TavilySearchProviderTest by testSuite {
    /** Answers every request with [reply] and records the requests. */
    class RecordingTavily(reply: TavilyReply = TavilyReply()) {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { request ->
            requests += request
            val headers = listOfNotNull(
                HttpHeaders.ContentType to listOf("application/json"),
                reply.retryAfter?.let { HttpHeaders.RetryAfter to listOf(it) },
            )
            respond(reply.body, reply.status, headersOf(*headers.toTypedArray()))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        fun provider() = TavilySearchProvider("tvly-test", http)

        fun sentJson() = Json.parseToJsonElement((requests.single().body as TextContent).text).jsonObject
    }

    suspend fun failedSearch(reply: TavilyReply): SearchError {
        val tavily = RecordingTavily(reply)
        val error = assertNotNull(tavily.provider().search(SearchQuery("koog agents"), limit = 5).leftOrNull(), "expected a search error")
        assertEquals(1, tavily.requests.size, "the provider itself never retries")
        return error
    }

    test("maps results in Tavily's order, with page text and publication date when Tavily has them") {
        val results = RecordingTavily().provider().search(SearchQuery("koog agents"), limit = 5)

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
        val tavily = RecordingTavily()
        tavily.provider().search(SearchQuery("koog agents"), limit = 5)

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
            val tavily = RecordingTavily()
            tavily.provider().search(SearchQuery("koog agents", recency), limit = 5)

            assertEquals(timeRange, tavily.sentJson()["time_range"]?.jsonPrimitive?.content)
        }
    }

    test("treats a publication date it can't read as unknown") {
        val tavily = RecordingTavily(
            TavilyReply(body = """{"results": [{"title": "Koog", "url": "https://koog.ai", "content": "Snippet.", "published_date": "sometime last year"}]}""")
        )

        val results = assertNotNull(tavily.provider().search(SearchQuery("koog agents"), limit = 5).getOrNull())
        assertNull(results.single().publishedDate)
    }

    test("treats blank page text as missing") {
        val tavily = RecordingTavily(
            TavilyReply(body = """{"results": [{"title": "Empty", "url": "https://example.com", "content": "Snippet.", "raw_content": "  "}]}""")
        )

        val results = assertNotNull(tavily.provider().search(SearchQuery("koog agents"), limit = 5).getOrNull())
        assertNull(results.single().content)
    }

    test("caps max_results at Tavily's limit") {
        val tavily = RecordingTavily()
        tavily.provider().search(SearchQuery("koog agents"), limit = 50)

        assertEquals(20, tavily.sentJson()["max_results"]?.jsonPrimitive?.int)
    }

    test("turns Tavily's error statuses into search errors, keeping Tavily's message") {
        val expected = mapOf(
            400 to SearchError.InvalidRequest::class,
            401 to SearchError.Unauthorized::class,
            404 to SearchError.Unexpected::class,
            422 to SearchError.InvalidRequest::class,
            429 to SearchError.RateLimited::class,
            432 to SearchError.QuotaExceeded::class,
            433 to SearchError.QuotaExceeded::class,
            500 to SearchError.Unavailable::class,
            503 to SearchError.Unavailable::class,
        )
        for ((status, type) in expected) {
            val error = failedSearch(TavilyReply(HttpStatusCode.fromValue(status), ERROR_BODY))

            assertEquals(type, error::class, "HTTP $status")
            assertContains(error.message.orEmpty(), "HTTP $status")
            assertContains(error.message.orEmpty(), "Tavily says no.")
        }
    }

    test("reads how long a rate limit asks to wait") {
        val withHeader = failedSearch(TavilyReply(HttpStatusCode.TooManyRequests, ERROR_BODY, retryAfter = "3"))
        val withoutHeader = failedSearch(TavilyReply(HttpStatusCode.TooManyRequests, ERROR_BODY))

        assertEquals(3.seconds, assertIs<SearchError.RateLimited>(withHeader).retryAfter)
        assertNull(assertIs<SearchError.RateLimited>(withoutHeader).retryAfter)
    }

    test("reports Tavily as unavailable when it can't be reached") {
        val unreachable = HttpClient(MockEngine { throw IOException("Connection refused") }) {
            install(ContentNegotiation) { json() }
        }

        val error = TavilySearchProvider("tvly-test", unreachable).search(SearchQuery("koog agents"), limit = 5).leftOrNull()

        assertIs<IOException>(assertIs<SearchError.Unavailable>(error).cause)
    }

    test("reports a response that can't be read as unexpected") {
        val error = failedSearch(TavilyReply(body = "<html>Bad gateway</html>"))

        assertIs<SearchError.Unexpected>(error)
    }

    test("rejects a blank API key or a chunk count outside 1 to 3 up front") {
        val http = RecordingTavily().http
        assertFailsWith<IllegalArgumentException> { TavilySearchProvider(" ", http) }
        assertFailsWith<IllegalArgumentException> { TavilySearchProvider("tvly-test", http, chunksPerSource = 0) }
        assertFailsWith<IllegalArgumentException> { TavilySearchProvider("tvly-test", http, chunksPerSource = 4) }
    }
}
