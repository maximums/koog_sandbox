package io.github.maximums.web

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Tavily Search API (`basic` depth: 1 credit per query). */
class TavilySearchProvider(
    private val apiKey: String,
    private val http: HttpClient,
) : SearchProvider {
    init {
        require(apiKey.isNotBlank()) { "Tavily API key is missing" }
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val request = TavilyRequest(query, maxResults = limit.coerceIn(1, MAX_RESULTS), searchDepth = "basic")
        val response = http.post(ENDPOINT) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "Tavily search failed (${response.status}): ${body.take(300)}" }

        return json.decodeFromString<TavilyResponse>(body).results
            .mapIndexed { rank, result -> SearchResult(result.url, result.title, result.content, rank) }
    }

    private companion object {
        const val ENDPOINT = "https://api.tavily.com/search"
        const val MAX_RESULTS = 20
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class TavilyRequest(
    val query: String,
    @SerialName("max_results") val maxResults: Int,
    @SerialName("search_depth") val searchDepth: String,
)

@Serializable
private data class TavilyResponse(val results: List<TavilyResult>)

@Serializable
private data class TavilyResult(val url: String, val title: String, val content: String)
