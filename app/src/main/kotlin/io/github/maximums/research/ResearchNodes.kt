package io.github.maximums.research

import ai.koog.agents.core.agent.context.agentInput
import ai.koog.agents.core.dsl.builder.AIAgentBuilderDslMarker
import ai.koog.agents.core.dsl.builder.AIAgentNodeDelegate
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.extension.ToolCalls
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.structure.StructuredResponse
import io.github.maximums.tools.WebSearchTool
import io.github.oshai.kotlinlogging.KotlinLogging
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
