package io.github.maximums.research

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaModels

import ai.koog.prompt.message.Message
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.decision.Answer
import io.github.maximums.decision.DecisionModel
import io.github.maximums.decision.Question
import io.github.maximums.decision.ScoreAnswer
import io.github.maximums.llm.ZaiModels
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import kotlin.reflect.cast
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val QUESTION = "Who builds Koog?"
private const val ANSWER = "JetBrains builds Koog (https://koog.ai)."
private const val KOOG = "https://koog.ai"

private const val DOGS = "https://dogs.example"
private val dogs = SearchResult(DOGS, "Dog breeds", "All about dogs.", rank = 1)

/**
 * Answers every search with one hit whose snippet names the query ("Found [koog]"), so the mock model can
 * tell which search's results it's looking at, then [extraHit] when given. Records the queries, including
 * ones searched at the same time.
 */
private class FakeSearch(private val extraHit: SearchResult? = null) : SearchProvider {
    val queries: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun search(query: SearchQuery, limit: Int): Either<ApiError, List<SearchResult>> {
        queries += query.text
        return (listOf(SearchResult(KOOG, "Koog", "Found [${query.text}]", rank = 0)) + listOfNotNull(extraHit)).right()
    }
}

/**
 * Rates a search result whose text mentions [DOGS] as most likely unrelated, and any other as most likely
 * helpful; with [fails], it can't rate anything. Records each state it's asked about, as text.
 */
private class FakeRelevance(private val fails: Boolean = false) : DecisionModel {
    val states: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun <A : Answer> ask(state: JsonElement, questions: Map<String, Question<A>>): Either<ApiError, Map<String, A>> {
        val text = state.jsonPrimitive.content
        states += text
        if (fails) return ApiError.Unavailable("Kev is down").left()

        val answer = if (DOGS in text) ScoreAnswer(0.1, listOf(0.9, 0.05, 0.05), 0.8) else ScoreAnswer(1.9, listOf(0.02, 0.06, 0.92), 0.8)
        return questions.mapValues { (_, question) -> question.answerType.cast(answer) }.right()
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

private fun agent(
    model: PromptExecutor,
    search: SearchProvider,
    pages: PageFetcher = FakePages(),
    decisions: DecisionModel? = null,
    llmModel: LLModel = OllamaModels.Alibaba.QWEN_3_8_27B,
) = researcherAgentProvider(
    executor = model,
    llmModel = llmModel,
    searchProvider = search,
    pageFetcher = pages,
    decisions = decisions,
)

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

    test("drops the search results the decision model rates unrelated before the model sees them") {
        val relevance = FakeRelevance()
        val model = PromptRecordingExecutor(getMockExecutor { mockLLMAnswer(ANSWER) onRequestContains PLANNED_RESULTS })

        assertEquals(ANSWER, agent(model, FakeSearch(extraHit = dogs), decisions = relevance).run(QUESTION))

        val results = model.prompts.last().messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
        val seen = results.joinToString { it.output }
        assertContains(seen, KOOG)
        assertTrue(DOGS !in seen, "the unrelated result reached the model: $seen")
        // Each result is rated on its own, as plain text with the question.
        assertEquals(setOf("Question: $QUESTION\nSearch result: Koog\n$KOOG\n$PLANNED_RESULTS", "Question: $QUESTION\nSearch result: Dog breeds\n$DOGS\nAll about dogs."), relevance.states.toSet())
    }

    test("keeps every search result when the decision model can't rate them") {
        val model = PromptRecordingExecutor(getMockExecutor { mockLLMAnswer(ANSWER) onRequestContains PLANNED_RESULTS })

        agent(model, FakeSearch(extraHit = dogs), decisions = FakeRelevance(fails = true)).run(QUESTION)

        val seen = model.prompts.last().messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().joinToString { it.output }
        assertContains(seen, KOOG)
        assertContains(seen, DOGS)
    }

    test("passes other tools' results to the model without rating them") {
        val relevance = FakeRelevance()
        val model = getMockExecutor {
            mockLLMToolCall(readPages, ReadPagesTool.Args(listOf(DOGS))) onRequestContains PLANNED_RESULTS
            mockLLMAnswer(ANSWER) onRequestContains "Text of [$DOGS]"
        }

        assertEquals(ANSWER, agent(model, FakeSearch(), decisions = relevance).run(QUESTION))
        assertTrue(relevance.states.none { "Text of" in it })
    }

    test("asks GLM for the search queries with the schema in the prompt, since Z.ai has no JSON schemas") {
        val search = FakeSearch()
        val model = PromptRecordingExecutor(
            getMockExecutor {
                mockLLMAnswer("""{"queries": ["koog maintainers"]}""") onCondition { "Found [" !in it }
                mockLLMAnswer(ANSWER) onCondition { "Found [" in it }
            }
        )

        assertEquals(ANSWER, agent(model, search, llmModel = ZaiModels.GLM_5_3_FLASH).run(QUESTION))

        val planning = model.prompts.first()
        assertNull(planning.params.schema, "a schema was sent natively")
        // Koog adds the JSON schema as a message of its own; only the schema quotes the field's name.
        assertContains(planning.messages.last().textContent(), "\"queries\"")
        assertEquals(listOf("koog maintainers"), search.queries)
    }
}
