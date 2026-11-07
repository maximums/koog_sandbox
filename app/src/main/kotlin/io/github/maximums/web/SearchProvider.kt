package io.github.maximums.web

/** [rank] is the 0-based position of the result within its query's results. */
data class SearchResult(val url: String, val title: String, val snippet: String, val rank: Int)

fun interface SearchProvider {
    suspend fun search(query: String, limit: Int): List<SearchResult>
}
