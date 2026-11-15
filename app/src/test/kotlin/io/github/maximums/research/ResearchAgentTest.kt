package io.github.maximums.research

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
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
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.Collections
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val QUESTION = "Who builds Koog?"
private const val ANSWER = "JetBrains builds Koog (https://koog.ai)."
private const val KOOG = "https://koog.ai"

/**
 * Answers every search with one hit whose snippet names the query ("Found [koog]"), so the mock model can
 * tell which search's results it's looking at. Records the queries, including ones searched at the same time.
 */
private class FakeSearch : SearchProvider {
    val queries: MutableList<String> = Collections.synchronizedList(mutableListOf())

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

/** Passes every request to [inner], recording each request's prompt. */
private class PromptRecordingExecutor(private val inner: PromptExecutor) : PromptExecutor() {
    val prompts = mutableListOf<Prompt>()

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        prompts += prompt
        return inner.execute(prompt, model, tools)
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        inner.executeStreaming(prompt, model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = inner.moderate(prompt, model)

    override fun close() = inner.close()
}

private fun agent(model: PromptExecutor, search: SearchProvider, pages: PageFetcher = FakePages()) =
    researcherAgentProvider(executor = model, searchProvider = search, pageFetcher = pages)

/** What the model gets back from the planned search when it couldn't write queries: the question searched as is. */
private const val PLANNED_RESULTS = "Found [$QUESTION]"

/**
 * Runs the real agent (system prompt, tools, strategy) against a scripted model. The mock model picks its
 * reply by the last message it gets: the request for search queries, then the results of the latest searches.
 *
 * Unless a test scripts the search queries the model writes first, it writes none that can be read, so the
 * question itself is searched, and the model starts from those results ([PLANNED_RESULTS]).
 */
val ResearchAgentTest by testSuite {

    test("answers from the planned searches' results when it needs no more tools") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMAnswer(ANSWER) onRequestContains PLANNED_RESULTS
        }

        val answer = agent(model, search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf(QUESTION), search.queries)
    }

    test("runs the search the model asks for and answers from its results") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMToolCall(webSearch, WebSearchTool.Args("koog maintainers")) onRequestContains PLANNED_RESULTS
            mockLLMAnswer(ANSWER) onRequestContains "Found [koog maintainers]"
        }

        val answer = agent(model, search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf(QUESTION, "koog maintainers"), search.queries)
    }

    test("keeps looping while the model asks for more searches") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMToolCall(webSearch, WebSearchTool.Args("koog")) onRequestContains PLANNED_RESULTS
            mockLLMToolCall(webSearch, WebSearchTool.Args("koog jetbrains")) onRequestContains "Found [koog]"
            mockLLMAnswer(ANSWER) onRequestContains "Found [koog jetbrains]"
        }

        val answer = agent(model, search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf(QUESTION, "koog", "koog jetbrains"), search.queries)
    }

    test("runs the tools in a reply that also has text, instead of taking the text as the answer") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMMixedResponse(
                toolCalls = listOf(webSearch to WebSearchTool.Args("koog maintainers")),
                responses = listOf("Let me search for that."),
            ) onRequestContains PLANNED_RESULTS
            mockLLMAnswer(ANSWER) onRequestContains "Found [koog maintainers]"
        }

        val answer = agent(model, search).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf(QUESTION, "koog maintainers"), search.queries)
    }

    test("reads a page the model picks from the planned searches' results, then answers from it") {
        val pages = FakePages()
        val model = getMockExecutor {
            mockLLMToolCall(readPages, ReadPagesTool.Args(listOf(KOOG), focus = "who builds Koog")) onRequestContains PLANNED_RESULTS
            mockLLMAnswer(ANSWER) onRequestContains "Text of [$KOOG]"
        }

        val answer = agent(model, FakeSearch(), pages).run(QUESTION)

        assertEquals(ANSWER, answer)
        assertEquals(listOf(PageRequest(listOf(KOOG), focus = "who builds Koog")), pages.requests)
    }

    test("asks for search queries in a request of its own, then starts the conversation with them as the model's searches") {
        val model = PromptRecordingExecutor(
            getMockExecutor {
                mockLLMAnswer("""{"queries": ["koog maintainers", "koog jetbrains"]}""") onRequestContains "web search queries"
                mockLLMAnswer(ANSWER) onCondition { "Found [" in it }
            }
        )

        assertEquals(ANSWER, agent(model, FakeSearch()).run(QUESTION))

        val (planning, research) = model.prompts
        val request = assertIs<Message.User>(planning.messages.single())
        assertContains(request.textContent(), "Write up to 5 web search queries")
        assertContains(request.textContent(), "Question: $QUESTION")

        // The conversation: the system prompt, the question, the planned searches as the model's calls, their results.
        val (system, question, plan) = research.messages
        assertIs<Message.System>(system)
        assertEquals(QUESTION, assertIs<Message.User>(question).textContent())
        val calls = assertIs<Message.Assistant>(plan).parts.filterIsInstance<MessagePart.Tool.Call>()
        assertEquals(listOf(WebSearchTool.NAME, WebSearchTool.NAME), calls.map { it.tool })
        assertEquals(listOf("koog maintainers", "koog jetbrains"), calls.map { Json.decodeFromString<WebSearchTool.Args>(it.args).query })
        val results = research.messages.drop(3).flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
        assertEquals(calls.map { it.id }.toSet(), results.map { it.id }.toSet())
        assertTrue(research.messages.none { "web search queries" in it.textContent() })
    }

    test("searches the question itself when the model's search queries can't be read, then goes on") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMAnswer("I'd search for koog maintainers.") onRequestContains "web search queries"
            mockLLMAnswer(ANSWER) onRequestContains PLANNED_RESULTS
        }

        assertEquals(ANSWER, agent(model, search).run(QUESTION))
        assertEquals(listOf(QUESTION), search.queries)
    }

    test("searches every query the model wrote, all at once") {
        val search = FakeSearch()
        val model = getMockExecutor {
            mockLLMAnswer("""{"queries": ["koog maintainers", "koog jetbrains", "koog license"]}""") onRequestContains "web search queries"
            mockLLMAnswer(ANSWER) onCondition { "Found [" in it }
        }

        assertEquals(ANSWER, agent(model, search).run(QUESTION))
        // They run at the same time, so they may finish in any order.
        assertEquals(setOf("koog maintainers", "koog jetbrains", "koog license"), search.queries.toSet())
        assertEquals(3, search.queries.size)
    }
}
