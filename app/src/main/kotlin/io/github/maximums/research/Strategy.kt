package io.github.maximums.research

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeAppendPrompt
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
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
import io.github.maximums.tools.ReadPagesTool
import io.github.maximums.tools.WebSearchTool
import io.github.maximums.web.PageFetcher
import io.github.maximums.web.SearchProvider
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

fun researcherAgentProvider(
    executor: PromptExecutor = simpleOllamaAIExecutor(),
    searchProvider: SearchProvider,
    pageFetcher: PageFetcher,
    limits: ResearchLimits = ResearchLimits(),
    logger: KLogger = KotlinLogging.logger {}
): GraphAIAgent<String, String> {
    val webSearch = WebSearchTool(searchProvider, limits.resultsPerQuery)
    val readPages = ReadPagesTool(pageFetcher, maxPagesPerCall = limits.maxPagesPerRound, maxCharsPerCall = limits.maxCharsPerRead)
    return AIAgent(
        promptExecutor = executor,
        llmModel = OllamaModels.Alibaba.QWEN_3_8_27B,
        strategy = strategyProvider(),
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
                println("onToolCallCompleted::Tool: ${eventContext.toolName} result: ${eventContext.toolResult}")
            }

            onToolCallFailed { eventContext ->
                println("onToolCallFailed::Tool called: ${eventContext.toolName} failed with: ${eventContext.error?.message}")
            }
        }
    }
}

/**
 * The research loop: the model gets the question, and on each turn either calls tools or answers. Tool
 * results go back to the model, and the loop repeats until it answers with text.
 */
fun strategyProvider(): AIAgentGraphStrategy<String, String> {
    // Loaded here rather than in the node, so a missing prompt fails when the agent is built, not mid-run.
    val systemPrompt = loadPrompt("researcher.md")

    return strategy("web-research") {
        val setupContext by nodeAppendPrompt<String>("setupContext") {
            system(systemPrompt)
        }
        val askModel by nodeLLMRequest()
        val runTools by nodeExecuteTools()
        val sendToolResults by nodeLLMSendToolResults()

        edge(nodeStart forwardTo setupContext)
        edge(setupContext forwardTo askModel)

        // Koog follows the first edge that matches, and a reply can hold both text and tool calls
        // ("Let me search for that" plus a webSearch call). Tool calls are checked first, so such a reply
        // runs its tools instead of ending the run with the preamble as the answer.
        edge(askModel forwardTo runTools onToolCalls { true })
        edge(askModel forwardTo nodeFinish onTextMessage { true })

        edge(runTools forwardTo sendToolResults)

        edge(sendToolResults forwardTo runTools onToolCalls { true })
        edge(sendToolResults forwardTo nodeFinish onTextMessage { true })
    }
}
