package io.github.maximums.web

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tavily Search API (`basic` depth: 1 credit per query). Each result carries the page's full text as Markdown
 * (`include_raw_content`) and its publication date (`include_published_date`) when Tavily has them, at no extra
 * credits. A query's [Recency] becomes Tavily's `time_range`; pages without a known date are kept.
 *
 * [chunksPerSource] (1–3) is how many ~500-character excerpts make up each result's snippet.
 * Failures come back as [SearchError]s. This class never retries; call it through `withRetry` for that.
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

    override suspend fun search(query: SearchQuery, limit: Int): Either<SearchError, List<SearchResult>> = either {
        val request = TavilyRequest(
            query = query.text,
            maxResults = limit.coerceIn(1, MAX_RESULTS),
            searchDepth = "basic",
            chunksPerSource = chunksPerSource,
            includeRawContent = "markdown",
            includePublishedDate = true,
            timeRange = query.recency?.toTimeRange(),
        )
        val response = try {
            http.post(ENDPOINT) {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (e: IOException) {
            raise(SearchError.Unavailable("Tavily could not be reached: ${e.message}", e))
        }

        ensure(response.status.isSuccess()) { response.toSearchError() }

        val body = try {
            response.body<TavilyResponse>()
        } catch (e: ContentConvertException) {
            raise(SearchError.Unexpected("Tavily sent a response that could not be read: ${e.message}", e))
        }

        body.toSearchResults()
    }

    private fun TavilyResponse.toSearchResults(): List<SearchResult> =
        results.mapIndexed { rank, result ->
            SearchResult(
                url = result.url,
                title = result.title,
                snippet = result.content,
                rank = rank,
                content = result.rawContent?.takeIf { it.isNotBlank() },
                publishedDate = result.publishedDate?.let(::parsePublishedDate),
            )
        }

    private fun Recency.toTimeRange(): String = when (this) {
        Recency.DAY -> "day"
        Recency.WEEK -> "week"
        Recency.MONTH -> "month"
        Recency.YEAR -> "year"
    }

    /** Tavily sends dates like `Tue, 11 Mar 2025 17:00:00 GMT`. A date it can't parse is treated as unknown. */
    private fun parsePublishedDate(value: String): LocalDate? =
        try {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate()
        } catch (e: DateTimeParseException) {
            null
        }

    /** Maps Tavily's documented error statuses to [SearchError]s, keeping Tavily's own message. */
    private suspend fun HttpResponse.toSearchError(): SearchError {
        val code = status.value
        val details = "(HTTP $code): ${bodyAsText().take(300)}"

        return when (code) {
            400, 422 -> SearchError.InvalidRequest("Tavily rejected the request $details")
            401 -> SearchError.Unauthorized("Tavily rejected the API key $details")
            429 -> SearchError.RateLimited("Tavily rate limit reached $details", retryAfter = retryAfter())
            432 -> SearchError.QuotaExceeded("Tavily key or plan credit limit is used up $details")
            433 -> SearchError.QuotaExceeded("Tavily pay-as-you-go spending limit is reached $details")
            in 500..599 -> SearchError.Unavailable("Tavily had a server error $details")
            else -> SearchError.Unexpected("Tavily search failed $details")
        }
    }

    /** Tavily sends `Retry-After` as whole seconds. */
    private fun HttpResponse.retryAfter(): Duration? =
        headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds

    private companion object {
        const val ENDPOINT = "https://api.tavily.com/search"
        const val MAX_RESULTS = 20
    }
}

@Serializable
private data class TavilyRequest(
    val query: String,
    @SerialName("max_results") val maxResults: Int,
    @SerialName("search_depth") val searchDepth: String,
    @SerialName("chunks_per_source") val chunksPerSource: Int,
    @SerialName("include_raw_content") val includeRawContent: String,
    @SerialName("include_published_date") val includePublishedDate: Boolean,
    @SerialName("time_range") val timeRange: String?,
)

@Serializable
private data class TavilyResponse(val results: List<TavilyResult>)

@Serializable
private data class TavilyResult(
    val url: String,
    val title: String,
    val content: String,
    @SerialName("raw_content") val rawContent: String? = null,
    @SerialName("published_date") val publishedDate: String? = null,
)
