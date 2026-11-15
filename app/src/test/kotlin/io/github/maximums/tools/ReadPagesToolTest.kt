package io.github.maximums.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.web.ApiError
import io.github.maximums.web.Page
import io.github.maximums.web.PageFetcher
import io.github.maximums.web.PageRequest
import io.github.maximums.web.Unreadable
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val KOOG = "https://koog.ai"
private const val GITHUB = "https://github.com/JetBrains/koog"
private const val DOCS = "https://docs.koog.ai"
private const val MISSING = "https://example.com/missing"

/**
 * Returns [errors] in order on successive reads, then reads every requested URL: as its entry in [pages], or as
 * unreadable when it has none. Records each request.
 */
private class RecordingFetcher(
    private val pages: Map<String, Page> = emptyMap(),
    private vararg val errors: ApiError,
) : PageFetcher {
    val requests = mutableListOf<PageRequest>()

    override suspend fun fetch(request: PageRequest): Either<ApiError, Map<String, Either<Unreadable, Page>>> {
        requests += request
        errors.getOrNull(requests.size - 1)?.let { return it.left() }
        return request.urls.associateWith { url -> pages[url]?.right() ?: Unreadable(url, "404 page not found").left() }.right()
    }
}

private fun readPagesTool(fetcher: PageFetcher, maxPagesPerCall: Int = 5) =
    ReadPagesTool(fetcher, maxPagesPerCall = maxPagesPerCall, maxCharsPerCall = 100)

/** Runs on a virtual clock, so retry waits take no real time. */
val ReadPagesToolTest by testSuite(testConfig = TestConfig.testScope(isEnabled = true)) {

    test("reads the URLs in one batch, each once, with the focus the model gave") {
        val fetcher = RecordingFetcher()
        readPagesTool(fetcher).execute(ReadPagesTool.Args(listOf(" $KOOG ", GITHUB, "", KOOG), focus = "who builds Koog"))

        assertEquals(listOf(PageRequest(listOf(KOOG, GITHUB), "who builds Koog")), fetcher.requests)
    }

    test("returns the pages it read with their dates, and the reason for each page it couldn't read") {
        val fetcher = RecordingFetcher(
            mapOf(
                KOOG to Page(KOOG, "# Koog", LocalDate.of(2025, 3, 11)),
                GITHUB to Page(GITHUB, "# Koog on GitHub"),
            )
        )

        val result = readPagesTool(fetcher).execute(ReadPagesTool.Args(listOf(KOOG, MISSING, GITHUB)))

        assertEquals(
            ReadPagesTool.Result(
                pages = listOf(
                    ReadPagesTool.PageText(KOOG, "# Koog", publishedDate = "2025-03-11"),
                    ReadPagesTool.PageText(GITHUB, "# Koog on GitHub"),
                ),
                unreadable = listOf(ReadPagesTool.UnreadablePage(MISSING, "404 page not found")),
            ),
            result,
        )
    }

    test("cuts a page longer than the call's character budget and says so") {
        val fetcher = RecordingFetcher(mapOf(KOOG to Page(KOOG, "x".repeat(150))))

        val page = readPagesTool(fetcher).execute(ReadPagesTool.Args(listOf(KOOG))).pages.single()

        assertEquals("x".repeat(100), page.content)
        assertTrue(page.truncated)
    }

    test("shares the character budget: a short page stays whole and the long ones split the rest") {
        val fetcher = RecordingFetcher(
            mapOf(
                KOOG to Page(KOOG, "k".repeat(150)),
                GITHUB to Page(GITHUB, "g".repeat(20)),
                DOCS to Page(DOCS, "d".repeat(150)),
            )
        )

        val pages = readPagesTool(fetcher).execute(ReadPagesTool.Args(listOf(KOOG, GITHUB, DOCS))).pages

        assertEquals(listOf(KOOG to 40, GITHUB to 20, DOCS to 40), pages.map { it.url to it.content.length })
        assertEquals(listOf(true, false, true), pages.map { it.truncated })
    }

    test("reads only as many pages as one call allows and reports the rest as not read") {
        val fetcher = RecordingFetcher(mapOf(KOOG to Page(KOOG, "# Koog"), GITHUB to Page(GITHUB, "# Koog on GitHub")))

        val result = readPagesTool(fetcher, maxPagesPerCall = 2).execute(ReadPagesTool.Args(listOf(KOOG, GITHUB, MISSING)))

        assertEquals(listOf(KOOG, GITHUB), fetcher.requests.single().urls)
        assertEquals(listOf(KOOG, GITHUB), result.pages.map { it.url })
        assertEquals(MISSING, result.unreadable.single().url)
        assertContains(result.unreadable.single().reason, "at most 2 pages per call")
    }

    test("answers a call without URLs with an error, without reading anything") {
        val fetcher = RecordingFetcher()

        val result = readPagesTool(fetcher).execute(ReadPagesTool.Args(listOf(" ", "")))

        assertNotNull(result.error)
        assertTrue(fetcher.requests.isEmpty())
    }

    test("retries a temporary failure before answering") {
        val fetcher = RecordingFetcher(mapOf(KOOG to Page(KOOG, "# Koog")), ApiError.RateLimited("slow down", retryAfter = 2.seconds))

        val result = readPagesTool(fetcher).execute(ReadPagesTool.Args(listOf(KOOG)))

        assertEquals(listOf(ReadPagesTool.PageText(KOOG, "# Koog")), result.pages)
        assertNull(result.error)
        assertEquals(2, fetcher.requests.size)
    }

    test("tells the model to stop reading pages when the key or credits are the problem") {
        for (error in listOf(ApiError.Unauthorized("bad key"), ApiError.QuotaExceeded("no credits"))) {
            val fetcher = RecordingFetcher(emptyMap(), error)

            val result = readPagesTool(fetcher).execute(ReadPagesTool.Args(listOf(KOOG)))

            assertContains(assertNotNull(result.error), "Don't call readPages again")
            assertTrue(result.pages.isEmpty())
            assertEquals(1, fetcher.requests.size, "${error::class.simpleName} should not be retried")
        }
    }

    test("tells the model reading is temporarily unavailable once the retries run out") {
        val down = ApiError.Unavailable("down")
        val fetcher = RecordingFetcher(emptyMap(), down, down, down)

        val result = readPagesTool(fetcher).execute(ReadPagesTool.Args(listOf(KOOG)))

        assertContains(assertNotNull(result.error), "temporarily unavailable")
        assertEquals(3, fetcher.requests.size)
    }

    test("rejects a page limit or character limit below 1 up front") {
        assertFailsWith<IllegalArgumentException> { ReadPagesTool(RecordingFetcher(), maxPagesPerCall = 0, maxCharsPerCall = 100) }
        assertFailsWith<IllegalArgumentException> { ReadPagesTool(RecordingFetcher(), maxPagesPerCall = 5, maxCharsPerCall = 0) }
    }
}
