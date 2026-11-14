package io.github.maximums.web

import arrow.core.left
import arrow.core.right
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tavily answers in its own order: GitHub first, though koog.ai was asked for first. */
private const val EXTRACT_RESPONSE = """
{
  "results": [
    {"url": "https://github.com/JetBrains/koog", "raw_content": "# Koog on GitHub\n\nSource code.", "published_date": null, "images": []},
    {"url": "https://koog.ai", "raw_content": "# Koog\n\nKoog is a Kotlin agent framework.", "published_date": "Tue, 11 Mar 2025 17:00:00 GMT"}
  ],
  "failed_results": [
    {"url": "https://example.com/missing", "error": "Failed to fetch url"}
  ],
  "response_time": 0.8,
  "request_id": "abc-123"
}
"""

private const val KOOG = "https://koog.ai"
private const val GITHUB = "https://github.com/JetBrains/koog"
private const val MISSING = "https://example.com/missing"

val TavilyPageFetcherTest by testSuite {
    fun extractTavily(reply: TavilyReply = TavilyReply(body = EXTRACT_RESPONSE)) = RecordingTavily(reply)

    fun RecordingTavily.fetcher() = TavilyPageFetcher("tvly-test", http)

    test("gives every requested URL its own outcome, whatever order Tavily answers in") {
        val outcomes = extractTavily().fetcher().fetch(PageRequest(listOf(KOOG, GITHUB, MISSING)))

        assertEquals(
            mapOf(
                KOOG to Page(KOOG, "# Koog\n\nKoog is a Kotlin agent framework.", LocalDate.of(2025, 3, 11)).right(),
                GITHUB to Page(GITHUB, "# Koog on GitHub\n\nSource code.").right(),
                MISSING to Unreadable(MISSING, "Failed to fetch url").left(),
            ).right(),
            outcomes,
        )
    }

    test("reports a page Tavily says nothing about, or returns without text, as unreadable") {
        val tavily = extractTavily(
            TavilyReply(body = """{"results": [{"url": "https://koog.ai", "raw_content": "   "}], "failed_results": []}""")
        )
        val unknown = "https://unknown.example"

        val outcomes = assertNotNull(tavily.fetcher().fetch(PageRequest(listOf(KOOG, unknown))).getOrNull())

        assertIs<Unreadable>(outcomes.getValue(KOOG).leftOrNull())
        assertIs<Unreadable>(outcomes.getValue(unknown).leftOrNull())
    }

    test("asks for whole pages as Markdown with publication dates, each URL once") {
        val tavily = extractTavily()
        tavily.fetcher().fetch(PageRequest(listOf(KOOG, GITHUB, KOOG)))

        val request = tavily.requests.single()
        assertEquals("https://api.tavily.com/extract", request.url.toString())
        assertEquals("Bearer tvly-test", request.headers[HttpHeaders.Authorization])

        val sent = tavily.sentJson()
        assertEquals(listOf(KOOG, GITHUB), sent.getValue("urls").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("basic", sent["extract_depth"]?.jsonPrimitive?.content)
        assertEquals("markdown", sent["format"]?.jsonPrimitive?.content)
        assertEquals(true, sent["include_published_date"]?.jsonPrimitive?.boolean)
        assertFalse("query" in sent, "no query without a focus")
        assertFalse("chunks_per_source" in sent, "no chunk count without a focus")
    }

    test("asks for only the most relevant excerpts when the request has a focus") {
        val tavily = extractTavily()
        tavily.fetcher().fetch(PageRequest(listOf(KOOG), focus = "who builds Koog"))

        assertEquals("who builds Koog", tavily.sentJson()["query"]?.jsonPrimitive?.content)
        assertEquals(3, tavily.sentJson()["chunks_per_source"]?.jsonPrimitive?.int)
    }

    test("answers an empty request without calling Tavily") {
        val tavily = extractTavily()

        assertEquals(emptyMap<String, Nothing>().right(), tavily.fetcher().fetch(PageRequest(emptyList())))
        assertTrue(tavily.requests.isEmpty())
    }

    test("fails the whole request with an API error when Tavily rejects it") {
        val unauthorized = extractTavily(TavilyReply(HttpStatusCode.Unauthorized, TAVILY_ERROR_BODY))
        val rateLimited = extractTavily(TavilyReply(HttpStatusCode.TooManyRequests, TAVILY_ERROR_BODY, retryAfter = "5"))

        assertIs<ApiError.Unauthorized>(unauthorized.fetcher().fetch(PageRequest(listOf(KOOG))).leftOrNull())
        assertIs<ApiError.RateLimited>(rateLimited.fetcher().fetch(PageRequest(listOf(KOOG))).leftOrNull())
    }

    test("rejects more than 20 URLs, a blank API key or a chunk count outside 1 to 5 up front") {
        val tavily = extractTavily()
        val tooMany = PageRequest((1..21).map { "https://example.com/$it" })

        assertFailsWith<IllegalArgumentException> { tavily.fetcher().fetch(tooMany) }
        assertFailsWith<IllegalArgumentException> { TavilyPageFetcher(" ", tavily.http) }
        assertFailsWith<IllegalArgumentException> { TavilyPageFetcher("tvly-test", tavily.http, chunksPerSource = 0) }
        assertFailsWith<IllegalArgumentException> { TavilyPageFetcher("tvly-test", tavily.http, chunksPerSource = 6) }
    }
}
