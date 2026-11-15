package io.github.maximums.web

import arrow.core.raise.Raise
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// What Tavily's endpoints have in common: how they're called, how they fail, and how they write dates.

internal const val TAVILY_API = "https://api.tavily.com"

/**
 * Posts [request] as JSON to a Tavily [endpoint] and reads the JSON reply. Every expected failure is raised as
 * an [ApiError]: an unreachable server, an error status, or a reply that can't be read.
 */
internal suspend inline fun <reified Req : Any, reified Res> Raise<ApiError>.callTavily(
    http: HttpClient,
    apiKey: String,
    endpoint: String,
    request: Req,
): Res = postJson(http, "$TAVILY_API/$endpoint", apiKey, request, service = "Tavily") { it.toApiError() }

internal suspend fun HttpResponse.toApiError(): ApiError {
    val code = status.value
    val details = errorDetails()

    return when (code) {
        400, 422 -> ApiError.InvalidRequest("Tavily rejected the request $details")
        401 -> ApiError.Unauthorized("Tavily rejected the API key $details")
        429 -> ApiError.RateLimited("Tavily rate limit reached $details", retryAfter = retryAfter())
        432 -> ApiError.QuotaExceeded("Tavily key or plan credit limit is used up $details")
        433 -> ApiError.QuotaExceeded("Tavily pay-as-you-go spending limit is reached $details")
        in 500..599 -> ApiError.Unavailable("Tavily had a server error $details")
        else -> ApiError.Unexpected("Tavily request failed $details")
    }
}

internal fun parseTavilyDate(value: String?): LocalDate? =
    value?.let {
        try {
            ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate()
        } catch (e: DateTimeParseException) {
            null
        }
    }
