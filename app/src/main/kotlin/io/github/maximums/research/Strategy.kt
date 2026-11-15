package io.github.maximums.research

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.asMermaidDiagram
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.llm.LLModel
import io.github.maximums.decision.DecisionModel
import io.github.maximums.tools.ReadPagesTool
import io.github.maximums.tools.WebSearchTool
import io.github.maximums.web.PageFetcher
import io.github.maximums.web.SearchProvider
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/** A research agent that runs [llmModel] through [executor]: by default Qwen 27B on the local Ollama. */
fun researcherAgentProvider(
    executor: PromptExecutor = simpleOllamaAIExecutor(),
    llmModel: LLModel = OllamaModels.Alibaba.QWEN_3_8_27B,
    searchProvider: SearchProvider,
    pageFetcher: PageFetcher,
    limits: ResearchLimits = ResearchLimits(),
    decisions: DecisionModel? = null,
    logger: KLogger = KotlinLogging.logger {}
): GraphAIAgent<String, String> {
    val webSearch = WebSearchTool(searchProvider, limits.resultsPerQuery)
    val readPages = ReadPagesTool(pageFetcher, maxPagesPerCall = limits.maxPagesPerRound, maxCharsPerCall = limits.maxCharsPerRead)
    return AIAgent(
        promptExecutor = executor,
        llmModel = llmModel,
        strategy = strategyProvider(maxQueries = limits.maxQueriesPerRound, decisions = decisions),
        toolRegistry = ToolRegistry {
            tool(webSearch)
            tool(readPages)
        },
        maxIterations = limits.maxRounds * 10 + limits.maxReportRewrites + 10,
    ) {
        install(Tracing) {
            addMessageProcessor(TraceFeatureMessageLogWriter(logger))
        }
        handleEvents {
            onToolCallStarting { eventContext ->
                println("onToolCallStarting::Tool called: ${eventContext.toolName} with args: ${eventContext.toolArgs}")
            }

            onToolCallCompleted { eventContext ->
                println("onToolCallCompleted::Tool: ${eventContext.toolName} result: ${eventContext.toolArgs}")
            }

            onToolCallFailed { eventContext ->
                println("onToolCallFailed::Tool called: ${eventContext.toolName} failed with: ${eventContext.error?.message}")
            }
        }
    }
}

/**
 * The research loop. First the model writes up to [maxQueries] search queries for the question, in a request
 * that stays out of the conversation. The agent's [WebSearchTool] runs them all at once, and the model starts
 * the research from their results, as if it had run those searches itself. On each turn it then either calls
 * tools or answers; tool results go back to it, and the loop repeats until it answers with text.
 *
 * With [decisions], search results it rates unrelated to the question are dropped before the model sees them.
 */
fun strategyProvider(maxQueries: Int, decisions: DecisionModel? = null): AIAgentGraphStrategy<String, String> {
    require(maxQueries >= 1) { "maxQueries must be at least 1, was $maxQueries" }

    val systemPrompt = loadPrompt("researcher.md")
    val queryInstructions = loadPrompt("search-queries.md").replace("{count}", maxQueries.toString())

    return strategy<String, String>("web-research") {
        val planSearches by nodeLLMRequestStructured<SearchQueries>("planSearches")
        val searchCalls by nodeSearchCalls("searchCalls", maxQueries = maxQueries)
        val setupContext by nodeStartResearch("setupContext", systemPrompt = systemPrompt)
        val runSearches by nodeExecuteTools("runSearches", parallel = true)
        val sendToolResults by nodeLLMSendToolResults()

        edge(nodeStart forwardTo planSearches transformed { question -> "$queryInstructions\n\nQuestion: $question" })
        edge(planSearches forwardTo searchCalls)
        edge(searchCalls forwardTo setupContext)
        edge(setupContext forwardTo runSearches)

        // remove the `if` later - always present
        if (decisions != null) {
            val filterResults by nodeFilterSearchResults("filterResults", decisions = decisions)
            edge(runSearches forwardTo filterResults)
            edge(filterResults forwardTo sendToolResults)
        } else {
            edge(runSearches forwardTo sendToolResults)
        }

        // Koog follows the first edge that matches, and a reply can hold both text and tool calls
        // ("Let me search for that" plus a webSearch call). Tool calls are checked first, so such a reply
        // runs its tools instead of ending the run with the preamble as the answer.
        edge(sendToolResults forwardTo runSearches onToolCalls { true })
        edge(sendToolResults forwardTo nodeFinish onTextMessage { true })
    }.also {
        println(it.asMermaidDiagram())
    }
}
