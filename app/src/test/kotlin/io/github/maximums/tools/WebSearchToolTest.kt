package io.github.maximums.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.web.ApiError
import io.github.maximums.web.Recency
import io.github.maximums.web.SearchProvider
import io.github.maximums.web.SearchQuery
import io.github.maximums.web.SearchResult
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Returns [errors] in order on successive searches, then [results]. Records each query and limit. */
private class RecordingProvider(
    private val results: List<SearchResult> = emptyList(),
    private vararg val errors: ApiError,
) : SearchProvider {
    val calls = mutableListOf<Pair<SearchQuery, Int>>()

    override suspend fun search(query: SearchQuery, limit: Int): Either<ApiError, List<SearchResult>> {
        calls += query to limit
        return errors.getOrNull(calls.size - 1)?.left() ?: results.right()
    }
}

/** Runs on a virtual clock, so retry waits take no real time. */
val WebSearchToolTest by testSuite(testConfig = TestConfig.testScope(isEnabled = true)) {

    test("passes the query and the configured result limit to the provider") {
        val provider = RecordingProvider()
        WebSearchTool(provider, resultsPerQuery = 7).execute(WebSearchTool.Args("koog agents"))

        assertEquals(listOf(SearchQuery("koog agents") to 7), provider.calls)
    }

    test("passes the recency the model asked for") {
        val provider = RecordingProvider()
        WebSearchTool(provider, resultsPerQuery = 5).execute(WebSearchTool.Args("koog release", WebSearchTool.Recency.WEEK))

        assertEquals(SearchQuery("koog release", Recency.WEEK), provider.calls.single().first)
    }

    test("returns compact hits in rank order, with publication dates") {
        val provider = RecordingProvider(
            listOf(
                SearchResult("https://github.com/JetBrains/koog", "Koog on GitHub", "Source code.", rank = 1),
                SearchResult(
                    "https://koog.ai", "Koog", "Koog is a Kotlin agent framework.", rank = 0,
                    content = "# Koog", publishedDate = LocalDate.of(2025, 3, 11),
                ),
            )
        )

        val result = WebSearchTool(provider, resultsPerQuery = 5).execute(WebSearchTool.Args("koog agents"))

        assertEquals(
            WebSearchTool.Result(
                query = "koog agents",
                results = listOf(
                    WebSearchTool.Hit("Koog", "https://koog.ai", "Koog is a Kotlin agent framework.", publishedDate = "2025-03-11"),
                    WebSearchTool.Hit("Koog on GitHub", "https://github.com/JetBrains/koog", "Source code."),
                ),
            ),
            result,
        )
    }

    test("answers a blank query with an error, without calling the provider") {
        val provider = RecordingProvider()

        val result = WebSearchTool(provider, resultsPerQuery = 5).execute(WebSearchTool.Args("  "))

        assertNotNull(result.error)
        assertTrue(provider.calls.isEmpty())
    }

    test("retries a temporary failure before answering") {
        val hit = SearchResult("https://koog.ai", "Koog", "Koog is a Kotlin agent framework.", rank = 0)
        val provider = RecordingProvider(listOf(hit), ApiError.RateLimited("slow down", retryAfter = 2.seconds))

        val result = WebSearchTool(provider, resultsPerQuery = 5).execute(WebSearchTool.Args("koog agents"))

        assertEquals(listOf(WebSearchTool.Hit("Koog", "https://koog.ai", "Koog is a Kotlin agent framework.")), result.results)
        assertEquals(2, provider.calls.size)
    }

    test("tells the model to stop searching when the key or credits are the problem") {
        for (error in listOf(ApiError.Unauthorized("bad key"), ApiError.QuotaExceeded("no credits"))) {
            val provider = RecordingProvider(emptyList(), error)

            val result = WebSearchTool(provider, resultsPerQuery = 5).execute(WebSearchTool.Args("koog agents"))

            assertContains(assertNotNull(result.error), "Don't call webSearch again")
            assertTrue(result.results.isEmpty())
            assertEquals(1, provider.calls.size, "${error::class.simpleName} should not be retried")
        }
    }

    test("tells the model search is temporarily unavailable once the retries run out") {
        val down = ApiError.Unavailable("down")
        val provider = RecordingProvider(emptyList(), down, down, down)

        val result = WebSearchTool(provider, resultsPerQuery = 5).execute(WebSearchTool.Args("koog agents"))

        assertContains(assertNotNull(result.error), "temporarily unavailable")
        assertEquals(3, provider.calls.size)
    }
}
