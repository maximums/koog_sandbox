package io.github.maximums.web

import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val TAVILY_RESPONSE = """
{
  "query": "koog agents",
  "answer": null,
  "images": [],
  "results": [
    {"title": "Koog", "url": "https://koog.ai", "content": "Koog is a Kotlin agent framework.", "score": 0.91, "raw_content": null, "favicon": "https://koog.ai/favicon.ico"},
    {"title": "Koog on GitHub", "url": "https://github.com/JetBrains/koog", "content": "Source code.", "score": 0.84, "raw_content": null}
  ],
  "response_time": 1.2,
  "request_id": "abc-123"
}
"""

val TavilySearchProviderTest by testSuite {
    class RecordingTavily(status: HttpStatusCode = HttpStatusCode.OK, body: String = TAVILY_RESPONSE) {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })

        fun sentJson() = Json.parseToJsonElement((requests.single().body as TextContent).text).jsonObject
    }

    test("maps results in Tavily's order") {
        val tavily = RecordingTavily()
        val results = TavilySearchProvider("tvly-test", tavily.http).search("koog agents", limit = 5)

        assertEquals(
            listOf(
                SearchResult("https://koog.ai", "Koog", "Koog is a Kotlin agent framework.", rank = 0),
                SearchResult("https://github.com/JetBrains/koog", "Koog on GitHub", "Source code.", rank = 1),
            ),
            results,
        )
    }

    test("sends the query with bearer auth") {
        val tavily = RecordingTavily()
        TavilySearchProvider("tvly-test", tavily.http).search("koog agents", limit = 5)

        val request = tavily.requests.single()
        assertEquals("https://api.tavily.com/search", request.url.toString())
        assertEquals("Bearer tvly-test", request.headers[HttpHeaders.Authorization])
        assertEquals("koog agents", tavily.sentJson()["query"]?.jsonPrimitive?.content)
        assertEquals(5, tavily.sentJson()["max_results"]?.jsonPrimitive?.int)
        assertEquals("basic", tavily.sentJson()["search_depth"]?.jsonPrimitive?.content)
    }

    test("caps max_results at Tavily's limit") {
        val tavily = RecordingTavily()
        TavilySearchProvider("tvly-test", tavily.http).search("koog agents", limit = 50)

        assertEquals(20, tavily.sentJson()["max_results"]?.jsonPrimitive?.int)
    }

    test("fails with Tavily's status and message when the request is rejected") {
        val tavily = RecordingTavily(HttpStatusCode.Unauthorized, """{"detail":{"error":"Unauthorized: missing or invalid API key."}}""")

        val error = assertFailsWith<IllegalStateException> {
            TavilySearchProvider("tvly-bad", tavily.http).search("koog agents", limit = 5)
        }
        assertContains(error.message.orEmpty(), "401")
        assertContains(error.message.orEmpty(), "invalid API key")
    }

    test("rejects a blank API key up front") {
        assertFailsWith<IllegalArgumentException> { TavilySearchProvider(" ", RecordingTavily().http) }
    }
}
