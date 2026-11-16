@file:JvmName("AppEntry")

package io.github.maximums

import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.ollama.client.OllamaModels
import io.github.maximums.decision.SystemOneClient
import io.github.maximums.llm.zai.ZaiModels
import io.github.maximums.llm.simpleZaiExecutor
import io.github.maximums.research.researcherAgentProvider
import io.github.maximums.web.TavilyPageFetcher
import io.github.maximums.web.TavilySearchProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json


//  curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent" \
//  -H 'Content-Type: application/json' \
//  -X POST \
//  -d '{
//    "contents": [
//      {
//        "parts": [
//          {
//            "text": "Explain how AI works in a few words"
//          }
//        ]
//      }
//    ]
//  }'

fun main(args: Array<out String>) = runBlocking {
    val tavilyApiKey = System.getenv("TAVILY_API_KEY")?.takeIf { it.isNotBlank() }
        ?: error("Set the TAVILY_API_KEY environment variable to your Tavily API key")

    val hClient = HttpClient(Apache5) {
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                }
            )
        }
    }
    // GLM-5.3-Flash on Z.ai when there's a key for it, otherwise Qwen on the local Ollama.
    val (executor, llmModel) = when (val zaiApiKey = System.getenv("ZAI_API_KEY")?.takeIf { it.isNotBlank() }) {
        null -> simpleOllamaAIExecutor() to OllamaModels.Alibaba.QWEN_3_8_27B
        else -> simpleZaiExecutor(zaiApiKey) to ZaiModels.GLM_5_3_FLASH
    }
    println("Researching with ${llmModel.id}")

    val agent = researcherAgentProvider(
        executor = executor,
        llmModel = llmModel,
        searchProvider = TavilySearchProvider(apiKey = tavilyApiKey, http = hClient),
        pageFetcher = TavilyPageFetcher(apiKey = tavilyApiKey, http = hClient),
        decisions = SystemOneClient.kev(hClient),
    )

    val userRequest = readln()
    val response = agent.run(userRequest, "Testing session")

    println("Got the following response: $response")
}