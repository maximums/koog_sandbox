package io.github.maximums.web

import arrow.core.Either

class DuckDuckGoSearchProvider : SearchProvider {
    override suspend fun search(query: SearchQuery, limit: Int): Either<SearchError, List<SearchResult>> =
        TODO("Query html.duckduckgo.com and parse the result list")
}
