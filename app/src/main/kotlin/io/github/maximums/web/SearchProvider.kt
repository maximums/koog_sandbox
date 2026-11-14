package io.github.maximums.web

import arrow.core.Either
import java.time.LocalDate

/** What to search for. [recency], when set, limits results to pages published within that period. */
data class SearchQuery(val text: String, val recency: Recency? = null)

/** How recent search results must be. Providers may keep pages whose date they can't tell. */
enum class Recency { DAY, WEEK, MONTH, YEAR }

/**
 * [rank] is the 0-based position of the result within its query's results.
 * [content] is the page's full text as Markdown when the provider returns it, or null when it doesn't.
 * [publishedDate] is when the page was published or last updated, as far as the provider can tell, or null.
 */
data class SearchResult(
    val url: String,
    val title: String,
    val snippet: String,
    val rank: Int,
    val content: String? = null,
    val publishedDate: LocalDate? = null,
)

/** Searches the web. Expected failures come back as an [ApiError]; an exception means a bug. */
fun interface SearchProvider {
    suspend fun search(query: SearchQuery, limit: Int): Either<ApiError, List<SearchResult>>
}
