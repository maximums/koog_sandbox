@file:JvmName("AppEntry")

package io.github.maximums

import io.github.maximums.research.researcherAgentProvider
import io.github.maximums.web.DuckDuckGoSearchProvider
import io.github.maximums.web.JinaReaderFetcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private const val TAVILY_API_KEY = ""

fun main(args: Array<out String>) = runBlocking {
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
        httpClient = hClient,
        apiKey = TAVILY_API_KEY,
    )

    val response = agent.run("Who is Lionel Messi?", "Testing session")

    println("Got the following response: $response")
}