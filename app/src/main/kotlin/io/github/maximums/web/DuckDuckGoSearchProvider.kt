package io.github.maximums.web

class DuckDuckGoSearchProvider : SearchProvider {
    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        TODO("Query html.duckduckgo.com and parse the result list")
}
