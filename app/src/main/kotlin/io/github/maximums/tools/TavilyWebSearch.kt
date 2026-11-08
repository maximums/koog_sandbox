package io.github.maximums.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

private const val BASE_URL = "https://api.tavily.com"

class TavilyWebSearch(
    private val httpClient: HttpClient,
    private val apiKey: String,
) : Tool<TavilyWebSearch.Args, TavilyWebSearch.Response>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Response>(),
    name = "webSearch",
    description = "Web search with Tavily"
) {

    // TODO separated it later, right now this class serves 2 purposes: 1. Tool args; 2. Tavily API request model
    @Serializable
    data class Args(
        val query: String,
        @SerialName("include_answer") val includeAnswer: String? = null,
        @SerialName("search_depth") val searchDepth: SearchDepth = SearchDepth.BASIC,
        @SerialName("chunks_per_source") val chunksPerSource: Int = 3,                      // 1 <= x <=  3
        @SerialName("max_results") val maxResults: Int = 10,                                // 0 <= x <= 20
        val topic: Topic = Topic.GENERAL,
        @SerialName("time_range") val timeRange: TimeRange? = null,
        @SerialName("start_date") val startDate: String? = null,                            // format YYYY-MM-DD
        @SerialName("end_date") val endDate: String? = null,                                // format YYYY-MM-DD
        @SerialName("include_published_date") val includePublishedDate: Boolean = false,    // the date is Tavily's best estimate
        @SerialName("filter_by_published_date") val filterByPublishedDate: Boolean = false,
        @SerialName("include_domains") val includeDomain: List<String> = emptyList(),
        @SerialName("exclude_domains") val excludeDomain: List<String> = emptyList(),
        @SerialName("include_domains_mode") val domainMode: DomainMode = DomainMode.PREFER,
        @SerialName("exact_match") val exactMatch: Boolean = false,
        @SerialName("safe_search") val safeSearch: Boolean = false,                         // for pussies xD
        @SerialName("auto_parameters") val autoParameters: Boolean = false,                 // automatically configure params based on the `query`
        @SerialName("include_usage") val apiCreditUsage: Boolean = true,
    )

    @Serializable
    enum class SearchDepth {
        @SerialName("advanced") ADVANCED,                                                   // 2 API credits for call(other options consume only 1 credit)
        @SerialName("basic") BASIC,
        @SerialName("fast") FAST,
        @SerialName("ultra-fast") ULTRA_FAST

    }

    @Serializable
    enum class Topic {
        @SerialName("general") GENERAL,
        @SerialName("news") NEWS,
        @SerialName("finance") FINANCE,
    }

    @Serializable
    enum class TimeRange {
        @SerialName("day") DAY,
        @SerialName("week") WEEK,
        @SerialName("month") MONTH,
        @SerialName("year") YEAR
    }

    @Serializable
    enum class DomainMode {
        @SerialName("restrict") RESTRICT,
        @SerialName("prefer") PREFER
    }

    @Serializable
    data class Response(
        val query: String,
        val answer: String? = null,                                                             // only when `include_answer` is set
        val images: List<Image> = emptyList(),                                                  // only when `include_images` is true
        val results: List<SearchResult>,
        @SerialName("response_time") val responseTime: Double,
        val usage: Usage? = null,                                                               // only when `include_usage` is true
        @SerialName("auto_parameters") val autoParameters: Map<String, JsonElement>? = null,    // only when `auto_parameters` is true
    )

    @Serializable
    data class Image(
        val url: String,
        val description: String? = null,
    )

    @Serializable
    data class SearchResult(
        val title: String,
        val url: String,
        val content: String,
        val score: Double,
    )

    @Serializable
    data class Usage(
        val credits: Int,
    )

    override suspend fun execute(args: Args): Response {
        val response = httpClient.post("$BASE_URL/search") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(args)
        }
        check(response.status.isSuccess()) { "Tavily search failed (${response.status}): ${response.bodyAsText().take(300)}" }

        return response.body()
    }
}
