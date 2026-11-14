package io.github.maximums.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.maximums.retry.RetryPolicy
import io.github.maximums.retry.withRetry
import io.github.maximums.web.SearchError
import io.github.maximums.web.SearchProvider
import io.github.maximums.web.SearchQuery
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.github.maximums.web.Recency as SearchRecency

private val logger = KotlinLogging.logger {}

/**
 * The LLM-facing web search tool. It owns only the contract the model sees (a small [Args] schema and a
 * compact [Result]); the actual search backend, its request/response models and its settings live behind
 * [SearchProvider].
 *
 * Searches that fail for a temporary reason are retried with [retryPolicy]. A search that still fails comes
 * back as [Result.error], written for the model, instead of an exception: Koog would turn an exception into a
 * failure message for the model anyway, without stopping the run.
 */
class WebSearchTool(
    private val provider: SearchProvider,
    private val resultsPerQuery: Int,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : Tool<WebSearchTool.Args, WebSearchTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = "webSearch",
    description = "Searches the web and returns the top results for a query, each with a title, URL and snippet, " +
        "and a publication date when one is known. Snippets are short excerpts chosen by the search engine, " +
        "not verified evidence. Dates are estimates and may be when the page was last updated.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("A precise, information-rich search query.")
        val query: String,

        @property:LLMDescription(
            "Only return pages published within this period. Use it when the question is about recent " +
                "events or the latest state of something; leave it out otherwise."
        )
        val recency: Recency? = null,
    )

    @Serializable
    enum class Recency {
        @SerialName("day") DAY,
        @SerialName("week") WEEK,
        @SerialName("month") MONTH,
        @SerialName("year") YEAR,
    }

    @Serializable
    data class Result(
        val query: String,
        val results: List<Hit> = emptyList(),
        /** Why the search failed, written for the model; null when it succeeded. */
        val error: String? = null,
    )

    @Serializable
    data class Hit(
        val title: String,
        val url: String,
        val snippet: String,
        /** ISO date (`2025-03-11`) the page was published or last updated, when known. */
        val publishedDate: String? = null,
    )

    override suspend fun execute(args: Args): Result {
        if (args.query.isBlank()) return Result(args.query, error = "The query is empty. Provide a search query.")

        val query = SearchQuery(text = args.query, recency = args.recency?.toSearchRecency())

        return withRetry(retryPolicy) { provider.search(query, resultsPerQuery) }.fold(
            ifLeft = { error ->
                logger.warn { "webSearch failed for '${args.query}': $error" }
                Result(args.query, error = error.forModel())
            },
            ifRight = { results ->
                Result(
                    query = args.query,
                    results = results.sortedBy { it.rank }.map {
                        Hit(title = it.title, url = it.url, snippet = it.snippet, publishedDate = it.publishedDate?.toString())
                    },
                )
            },
        )
    }
}

private fun WebSearchTool.Recency.toSearchRecency(): SearchRecency = when (this) {
    WebSearchTool.Recency.DAY -> SearchRecency.DAY
    WebSearchTool.Recency.WEEK -> SearchRecency.WEEK
    WebSearchTool.Recency.MONTH -> SearchRecency.MONTH
    WebSearchTool.Recency.YEAR -> SearchRecency.YEAR
}

private fun SearchError.forModel(): String = when (this) {
    is SearchError.Unauthorized, is SearchError.QuotaExceeded ->
        "Web search is unavailable for the rest of this task. Don't call webSearch again; " +
            "answer with what you already have."

    is SearchError.RateLimited, is SearchError.Unavailable ->
        "Web search is temporarily unavailable. Try again later, or continue with what you already have."

    is SearchError.InvalidRequest, is SearchError.Unexpected ->
        "This search failed. Try a different query."
}
