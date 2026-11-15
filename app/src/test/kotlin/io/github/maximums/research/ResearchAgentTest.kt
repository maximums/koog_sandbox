package io.github.maximums.research

import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import arrow.core.Either
import arrow.core.right
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.tools.ReadPagesTool
import io.github.maximums.tools.WebSearchTool
import io.github.maximums.web.ApiError
import io.github.maximums.web.Page
import io.github.maximums.web.PageFetcher
import io.github.maximums.web.PageRequest
import io.github.maximums.web.SearchProvider
import io.github.maximums.web.SearchQuery
import io.github.maximums.web.SearchResult
import io.github.maximums.web.Unreadable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val QUESTION = "Who builds Koog?"
private const val ANSWER = "JetBrains builds Koog (https://koog.ai)."
private const val KOOG = "https://koog.ai"

/**
 * Answers every search with one hit whose snippet names the query ("Found [koog]"), so the mock model can
 * tell which search's results it's looking at. Records the queries.
 */
private class FakeSearch : SearchProvider {
    val queries = mutableListOf<String>()

    override suspend fun search(query: SearchQuery, limit: Int): Either<ApiError, List<SearchResult>> {
        queries += query.text
        return listOf(SearchResult(KOOG, "Koog", "Found [${query.text}]", rank = 0)).right()
    }
}

/** Reads every URL as a page whose text names it ("Text of [https://koog.ai]"). Records the requests. */
private class FakePages : PageFetcher {
    val requests = mutableListOf<PageRequest>()

    override suspend fun fetch(request: PageRequest): Either<ApiError, Map<String, Either<Unreadable, Page>>> {
        requests += request
        return request.urls.associateWith { url -> Page(url, "Text of [$url]").right() }.right()
    }
}

// Only used to script the model's tool calls. The agent runs the tools it registered itself.
private val webSearch = WebSearchTool(SearchProvider { _, _ -> emptyList<SearchResult>().right() }, resultsPerQuery = 5)
private val readPages = ReadPagesTool(
    PageFetcher { emptyMap<String, Either<Unreadable, Page>>().right() },
    maxPagesPerCall = 5,
    maxCharsPerCall = 100,
)

private fun agent(model: PromptExecutor, search: SearchProvider, pages: PageFetcher = FakePages()) =
    researcherAgentProvider(executor = model, searchProvider = search, pageFetcher = pages)

/**
 * Runs the real agent (system prompt, tools, strategy) against a scripted model. The mock model picks its
 * reply by the last message it gets: first the question, then the results of the latest tool calls.
 */
val ResearchAgentTest by testSuite {

    test("answers straight away when the model needs no tools") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMAnswer(ANSWER) onRequestEquals QUESTION
        }

        val answer = agent(model, search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertTrue(search.queries.isEmpty())
    }

    test("runs the search the model asks for and answers from its results") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMToolCall(webSearch, WebSearchTool.Args("koog maintainers")) onRequestEquals QUESTION
            mockLLMAnswer(ANSWER) onRequestContains "Found [koog maintainers]"
        }

        val answer = agent(model, search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf("koog maintainers"), search.queries)
    }

    test("keeps looping while the model asks for more searches") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMToolCall(webSearch, WebSearchTool.Args("koog")) onRequestEquals QUESTION
            mockLLMToolCall(webSearch, WebSearchTool.Args("koog jetbrains")) onRequestContains "Found [koog]"
            mockLLMAnswer(ANSWER) onRequestContains "Found [koog jetbrains]"
        }

        val answer = agent(model, search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf("koog", "koog jetbrains"), search.queries)
    }

    test("runs the tools in a reply that also has text, instead of taking the text as the answer") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMMixedResponse(
                toolCalls = listOf(webSearch to WebSearchTool.Args("koog maintainers")),
                responses = listOf("Let me search for that."),
            ) onRequestEquals QUESTION
            mockLLMAnswer(ANSWER) onRequestContains "Found [koog maintainers]"
        }

        val answer = agent(model, search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf("koog maintainers"), search.queries)
    }

    test("reads the page the model picks from the search results, then answers from it") {
        val search = FakeSearch()
        val pages = FakePages()
        val model = getMockExecutor {
            mockLLMToolCall(webSearch, WebSearchTool.Args("koog maintainers")) onRequestEquals QUESTION
            mockLLMToolCall(readPages, ReadPagesTool.Args(listOf(KOOG), focus = "who builds Koog")) onRequestContains
                "Found [koog maintainers]"
            mockLLMAnswer(ANSWER) onRequestContains "Text of [$KOOG]"
        }

        val answer = agent(model, search, pages).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf(PageRequest(listOf(KOOG), focus = "who builds Koog")), pages.requests)
    }
}
