package io.github.maximums.research

import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.web.SearchResult
import kotlin.test.assertEquals

val PageSelectionTest by testSuite {
    fun result(url: String, rank: Int) = SearchResult(url, title = url, snippet = "", rank = rank)

    test("normalizeUrl treats cosmetic URL variants as the same page") {
        val variants = listOf(
            "https://example.com/a",
            "http://www.example.com/a/",
            "https://EXAMPLE.com/a#section",
            "https://example.com/a?utm_source=x&gclid=y",
        )
        assertEquals(setOf("example.com/a"), variants.map(::normalizeUrl).toSet())
    }

    test("normalizeUrl keeps meaningful query parameters") {
        assertEquals("example.com/search?q=koog", normalizeUrl("https://example.com/search?q=koog&utm_medium=email"))
    }

    test("takes the best-ranked result of every query before second-ranked ones") {
        val results = listOf(
            result("https://a.com/1", 0), result("https://a.com/2", 1),
            result("https://b.com/1", 0), result("https://b.com/2", 1),
        )
        val selected = selectPages(results, seenUrls = emptySet(), budget = 3)
        assertEquals(listOf("https://a.com/1", "https://b.com/1", "https://a.com/2"), selected.map { it.url })
    }

    test("drops duplicates and already-seen pages") {
        val results = listOf(
            result("https://a.com/1", 0),
            result("https://www.a.com/1/", 1),
            result("https://b.com/1", 0),
        )
        val selected = selectPages(results, seenUrls = setOf("b.com/1"), budget = 10)
        assertEquals(listOf("https://a.com/1"), selected.map { it.url })
    }
}
