package io.github.maximums.research

import ai.koog.agents.testing.tools.getMockExecutor
import arrow.core.Either
import arrow.core.right
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.tools.WebSearchTool
import io.github.maximums.web.ApiError
import io.github.maximums.web.SearchProvider
import io.github.maximums.web.SearchQuery
import io.github.maximums.web.SearchResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val QUESTION = "Who builds Koog?"
private const val ANSWER = "JetBrains builds Koog (https://koog.ai)."

/**
 * Answers every search with one hit whose snippet names the query ("Found [koog]"), so the mock model can
 * tell which search's results it's looking at. Records the queries.
 */
private class FakeSearch : SearchProvider {
    val queries = mutableListOf<String>()

    override suspend fun search(query: SearchQuery, limit: Int): Either<ApiError, List<SearchResult>> {
        queries += query.text
        return listOf(SearchResult("https://koog.ai", "Koog", "Found [${query.text}]", rank = 0)).right()
    }
}

/** Only used to script the model's tool calls. The agent runs the webSearch tool it registered itself. */
private val webSearch = WebSearchTool(SearchProvider { _, _ -> emptyList<SearchResult>().right() }, resultsPerQuery = 5)

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

        val answer = researcherAgentProvider(executor = model, searchProvider = search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertTrue(search.queries.isEmpty())
    }

    test("runs the search the model asks for and answers from its results") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMToolCall(webSearch, WebSearchTool.Args("koog maintainers")) onRequestEquals QUESTION
            mockLLMAnswer(ANSWER) onRequestContains "Found [koog maintainers]"
        }

        val answer = researcherAgentProvider(executor = model, searchProvider = search).run(QUESTION)

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

        val answer = researcherAgentProvider(executor = model, searchProvider = search).run(QUESTION)

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

        val answer = researcherAgentProvider(executor = model, searchProvider = search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf("koog maintainers"), search.queries)
    }
}
