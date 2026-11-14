package io.github.maximums.research

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeAppendPrompt
import ai.koog.agents.core.dsl.extension.nodeExecuteSingleTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestWithoutTools
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaModels
import io.github.maximums.tools.WebSearchTool
import io.github.maximums.web.SearchProvider
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

fun researcherAgentProvider(
    executor: PromptExecutor = simpleOllamaAIExecutor(),
    searchProvider: SearchProvider,
    limits: ResearchLimits = ResearchLimits(),
    logger: KLogger = KotlinLogging.logger {}
): GraphAIAgent<String, String> {
    val webSearch = WebSearchTool(searchProvider, limits.resultsPerQuery)
    return AIAgent(
        promptExecutor = executor,
        llmModel = OllamaModels.Alibaba.QWEN_3_8_27B,
        strategy = strategyProvider(limits, webSearch),
        toolRegistry = ToolRegistry {
            tool(webSearch)
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

fun strategyProvider(
    limits: ResearchLimits,
    webSearch: WebSearchTool,
): AIAgentGraphStrategy<String, String> {
    // Loaded here rather than in the node, so a missing prompt fails when the agent is built, not mid-run.
    val systemPrompt = loadPrompt("researcher.md")

    return strategy("web-research") {
        val setupContext by nodeAppendPrompt<String>("setupContext") {
            system(systemPrompt)
        }
        val nodeSendMainQuery by nodeLLMRequest()
        val execWebSearchToolNode by nodeExecuteSingleTool(tool = webSearch)
        val answerNode by nodeLLMRequestWithoutTools()

        edge(nodeStart forwardTo setupContext)
        edge(setupContext forwardTo execWebSearchToolNode transformed { WebSearchTool.Args(query = it) })
        edge(execWebSearchToolNode forwardTo answerNode transformed { agentInput as String })
        edge(answerNode forwardTo nodeFinish onTextMessage { true })

//        edge(setupContext forwardTo nodeSendMainQuery)
//        edge(nodeSendMainQuery forwardTo nodeFinish onTextMessage { true })
    }
}
