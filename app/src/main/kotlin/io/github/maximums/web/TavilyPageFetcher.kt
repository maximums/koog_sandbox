package io.github.maximums.web

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.ktor.client.HttpClient
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reads pages with Tavily Extract (`basic` depth: 1 credit per 5 pages read, failed pages are free), up to
 * [MAX_URLS] per request, as Markdown with their publication date when Tavily has it. With a
 * [PageRequest.focus], each page comes back as only its [chunksPerSource] (1–5) most relevant excerpts.
 *
 * Failures come back as an [ApiError] for the whole request, or [Unreadable] for a single page.
 * This class never retries; call it through `withRetry` for that.
 */
class TavilyPageFetcher(
    private val apiKey: String,
    private val http: HttpClient,
    private val chunksPerSource: Int = 3,
) : PageFetcher {

    init {
        require(apiKey.isNotBlank()) { "Tavily API key is missing" }
        require(chunksPerSource in 1..5) { "chunksPerSource must be between 1 and 5, was $chunksPerSource" }
    }

    override suspend fun fetch(request: PageRequest): Either<ApiError, Map<String, Either<Unreadable, Page>>> {
        val urls = request.urls.distinct()
        if (urls.isEmpty()) return emptyMap<String, Either<Unreadable, Page>>().right()

        require(urls.size <= MAX_URLS) { "Tavily reads at most $MAX_URLS URLs per request, got ${urls.size}" }

        val focus = request.focus?.takeIf { it.isNotBlank() }
        val extract = TavilyExtractRequest(
            urls = urls,
            extractDepth = "basic",
            format = "markdown",
            includePublishedDate = true,
            query = focus,
            chunksPerSource = focus?.let { chunksPerSource },
        )

        return either {
            callTavily<TavilyExtractRequest, TavilyExtractResponse>(http, apiKey, "extract", extract).toOutcomes(urls)
        }
    }

    /**
     * Gives every requested URL its outcome. Tavily lists results in its own order but echoes each URL exactly
     * as it was sent (even one that redirects), so they're matched by URL.
     */
    private fun TavilyExtractResponse.toOutcomes(requested: List<String>): Map<String, Either<Unreadable, Page>> {
        val pages = results.associateBy { it.url }
        val failures = failedResults.associateBy { it.url }

        return requested.associateWith { url ->
            val page = pages[url]
            val text = page?.rawContent?.takeIf { it.isNotBlank() }

            when {
                text != null -> Page(url, text, parseTavilyDate(page.publishedDate)).right()
                url in failures -> Unreadable(url, failures.getValue(url).error ?: "Tavily couldn't read this page").left()
                page != null -> Unreadable(url, "Tavily read the page but found no text").left()
                else -> Unreadable(url, "Tavily returned nothing for this page").left()
            }
        }
    }

    companion object {
        /** The most URLs Tavily Extract reads in one request. */
        const val MAX_URLS = 20
    }
}

/** [query] and [chunksPerSource] are left out of the JSON when there's no focus. */
@Serializable
private data class TavilyExtractRequest(
    val urls: List<String>,
    @SerialName("extract_depth") val extractDepth: String,
    val format: String,
    @SerialName("include_published_date") val includePublishedDate: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val query: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("chunks_per_source") val chunksPerSource: Int? = null,
)

@Serializable
private data class TavilyExtractResponse(
    val results: List<TavilyExtractResult> = emptyList(),
    @SerialName("failed_results") val failedResults: List<TavilyFailedResult> = emptyList(),
)

@Serializable
private data class TavilyExtractResult(
    val url: String,
    @SerialName("raw_content") val rawContent: String? = null,
    @SerialName("published_date") val publishedDate: String? = null,
)

@Serializable
private data class TavilyFailedResult(val url: String, val error: String? = null)
