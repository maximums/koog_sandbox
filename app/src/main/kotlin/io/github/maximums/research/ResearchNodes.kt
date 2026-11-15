package io.github.maximums.research

import ai.koog.agents.core.agent.context.agentInput
import ai.koog.agents.core.dsl.builder.AIAgentBuilderDslMarker
import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.ToolCalls
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.structure.StructuredResponse
import io.github.maximums.decision.DecisionModel
import io.github.maximums.decision.Score
import io.github.maximums.decision.ask
import io.github.maximums.tools.WebSearchTool
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/** What the model answers with when it writes the search queries for a question. */
@Serializable
@SerialName("SearchQueries")
internal data class SearchQueries(
    @property:LLMDescription("The search queries.")
    val queries: List<String>,
)

/**
 * A node that turns the model's [SearchQueries] reply into one [WebSearchTool] call per query, at most
 * [maxQueries], for `nodeExecuteTools` to run. When the reply can't be read or has no queries, it searches the
 * agent's input (the user's question) itself.
 */
@AIAgentBuilderDslMarker
internal fun nodeSearchCalls(
    name: String? = null,
    maxQueries: Int,
): AIAgentNodeDelegate<Result<StructuredResponse<SearchQueries>>, ToolCalls> {
    require(maxQueries >= 1) { "maxQueries must be at least 1, was $maxQueries" }

    return node(name) { reply ->
        val question = agentInput<String>()

        reply.onFailure { logger.warn(it) { "The search queries for '$question' couldn't be read" } }

        val queries = reply.getOrNull()?.data?.queries.orEmpty()
            .map(String::trim).filter(String::isNotEmpty).distinct().take(maxQueries)
            .ifEmpty { listOf(question) }

        logger.info { "Planned searches for '$question': $queries" }

        ToolCalls(
            queries.mapIndexed { i, query ->
                MessagePart.Tool.Call(
                    id = "planned-search-$i",
                    tool = WebSearchTool.NAME,
                    args = Json.encodeToString(WebSearchTool.Args(query))
                )
            }
        )
    }
}

/**
 * A node that starts the conversation the model researches in: [systemPrompt], the user's question (the
 * agent's input), and the [ToolCalls] it gets as the model's own reply. Running those calls and sending their
 * results (`nodeExecuteTools`, then `nodeLLMSendToolResults`) then reads to the model as searches it chose.
 *
 * It replaces the whole history, so whatever came before, such as the request for the search queries and its
 * reply, stays out of the conversation.
 */
@AIAgentBuilderDslMarker
internal fun nodeStartResearch(
    name: String? = null,
    systemPrompt: String,
): AIAgentNodeDelegate<ToolCalls, ToolCalls> = node(name) { calls ->
    val question = agentInput<String>()

    llm.writeSession {
        prompt = prompt.copy(messages = emptyList())
        appendPrompt {
            system(systemPrompt)
            user(question)
            assistant(calls.toolCalls)
        }
    }

    calls
}

/** How useful a search result is for answering the question; a result most likely at level 0 is dropped. */
private val RELEVANCE = Score(
    "How useful the search result is for answering the question.",
    listOf("Unrelated to the question", "Related, but doesn't help answer it", "Helps answer the question"),
)

private val toolJson = Json { ignoreUnknownKeys = true }

/**
 * A node that drops, from the [WebSearchTool] results it gets, the hits [decisions] rates as more likely than
 * not unrelated to the user's question (the agent's input), so they don't take up the model's context. Each
 * hit is rated on its own, with the question, title, URL and snippet as plain text.
 *
 * Other tools' results, failed searches and hits that can't be rated (the decision model failed) pass through
 * unchanged, so a broken decision model can't empty the results.
 */
@AIAgentBuilderDslMarker
internal fun nodeFilterSearchResults(
    name: String? = null,
    decisions: DecisionModel,
): AIAgentNodeDelegate<ReceivedToolResults, ReceivedToolResults> = node(name) { received ->
    val question = agentInput<String>()
    ReceivedToolResults(received.toolResults.map { it.withRelevantHits(question, decisions) })
}

private suspend fun ReceivedToolResult.withRelevantHits(question: String, decisions: DecisionModel): ReceivedToolResult {
    if (tool != WebSearchTool.NAME) return this

    val search = runCatching { toolJson.decodeFromString<WebSearchTool.Result>(output) }.getOrNull()

    if (search == null || search.results.isEmpty()) return this

    val relevant = coroutineScope {
        search.results.map { hit -> async { hit.takeUnless { decisions.isUnrelated(question, it) } } }.awaitAll()
    }.filterNotNull()
    logger.info { "Kept ${relevant.size} of ${search.results.size} results for '${search.query}'" }

    // The model reads a tool result's parts, or its output when it has none, so both change.
    val filtered = toolJson.encodeToString(search.copy(results = relevant))
    return copy(output = filtered, parts = listOf(MessagePart.Text(filtered)))
}

/** Whether this rates [hit] as more likely than not unrelated to [question]; false when it can't rate it. */
private suspend fun DecisionModel.isUnrelated(question: String, hit: WebSearchTool.Hit): Boolean =
    ask("Question: $question\nSearch result: ${hit.title}\n${hit.url}\n${hit.snippet}", mapOf("relevance" to RELEVANCE)).fold(
        ifLeft = { error ->
            logger.warn { "Couldn't rate ${hit.url} against the question, keeping it: $error" }
            false
        },
        ifRight = { answers -> answers.getValue("relevance").probabilities[0] >= 0.5 },
    )
