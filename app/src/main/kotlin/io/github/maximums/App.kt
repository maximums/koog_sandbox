@file:JvmName("AppEntry")

package io.github.maximums

import io.github.maximums.research.researcherAgentProvider
import io.github.maximums.web.DuckDuckGoSearchProvider
import io.github.maximums.web.JinaReaderFetcher
import io.github.maximums.web.TavilySearchProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

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
    val agent = researcherAgentProvider(
        searchProvider = TavilySearchProvider(apiKey = tavilyApiKey, http = hClient),
    )

    val response = agent.run("Who is Lionel Messi?", "Testing session")

    println("Got the following response: $response")
}