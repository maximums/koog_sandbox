package io.github.maximums.web

import arrow.core.Either
import arrow.core.raise.either
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tavily Search API (`basic` depth: 1 credit per query). Each result carries the page's full text as Markdown
 * (`include_raw_content`) and its publication date (`include_published_date`) when Tavily has them, at no extra
 * credits. A query's [Recency] becomes Tavily's `time_range`; pages without a known date are kept.
 *
 * [chunksPerSource] (1–3) is how many ~500-character excerpts make up each result's snippet.
 * Failures come back as [ApiError]s. This class never retries; call it through `withRetry` for that.
 */
class TavilySearchProvider(
    private val apiKey: String,
    private val http: HttpClient,
    private val chunksPerSource: Int = 2,
) : SearchProvider {

    init {
        require(apiKey.isNotBlank()) { "Tavily API key is missing" }
        require(chunksPerSource in 1..3) { "chunksPerSource must be between 1 and 3, was $chunksPerSource" }
    }

    override suspend fun search(query: SearchQuery, limit: Int): Either<ApiError, List<SearchResult>> = either {
        val request = TavilySearchRequest(
            query = query.text,
            maxResults = limit.coerceIn(1, MAX_RESULTS),
            searchDepth = "basic",
            chunksPerSource = chunksPerSource,
            includeRawContent = "markdown",
            includePublishedDate = true,
            timeRange = query.recency?.toTimeRange(),
        )

        callTavily<TavilySearchRequest, TavilySearchResponse>(http, apiKey, "search", request).toSearchResults()
    }

    private fun TavilySearchResponse.toSearchResults(): List<SearchResult> =
        results.mapIndexed { rank, result ->
            SearchResult(
                url = result.url,
                title = result.title,
                snippet = result.content,
                rank = rank,
                content = result.rawContent?.takeIf { it.isNotBlank() },
                publishedDate = parseTavilyDate(result.publishedDate),
            )
        }

    private fun Recency.toTimeRange(): String = when (this) {
        Recency.DAY -> "day"
        Recency.WEEK -> "week"
        Recency.MONTH -> "month"
        Recency.YEAR -> "year"
    }

    private companion object {
        const val MAX_RESULTS = 20
    }
}

@Serializable
private data class TavilySearchRequest(
    val query: String,
    @SerialName("max_results") val maxResults: Int,
    @SerialName("search_depth") val searchDepth: String,
    @SerialName("chunks_per_source") val chunksPerSource: Int,
    @SerialName("include_raw_content") val includeRawContent: String,
    @SerialName("include_published_date") val includePublishedDate: Boolean,
    @SerialName("time_range") val timeRange: String?,
)

@Serializable
private data class TavilySearchResponse(val results: List<TavilySearchResult>)

@Serializable
private data class TavilySearchResult(
    val url: String,
    val title: String,
    val content: String,
    @SerialName("raw_content") val rawContent: String? = null,
    @SerialName("published_date") val publishedDate: String? = null,
)
