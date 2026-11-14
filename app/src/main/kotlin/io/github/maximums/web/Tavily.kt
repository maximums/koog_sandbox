package io.github.maximums.web

import arrow.core.raise.Raise
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
import java.io.IOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
): Res {
    val response = try {
        http.post("$TAVILY_API/$endpoint") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    } catch (e: IOException) {
        raise(ApiError.Unavailable("Tavily could not be reached: ${e.message}", e))
    }

    ensure(response.status.isSuccess()) { response.toApiError() }

    return try {
        response.body<Res>()
    } catch (e: ContentConvertException) {
        raise(ApiError.Unexpected("Tavily sent a response that could not be read: ${e.message}", e))
    }
}

/** Maps Tavily's documented error statuses to [ApiError]s, keeping Tavily's own message. */
internal suspend fun HttpResponse.toApiError(): ApiError {
    val code = status.value
    val details = "(HTTP $code): ${bodyAsText().take(300)}"

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

/** Tavily sends `Retry-After` as whole seconds. */
private fun HttpResponse.retryAfter(): Duration? =
    headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds

/** Tavily sends dates like `Tue, 11 Mar 2025 17:00:00 GMT`. A date it can't parse is treated as unknown. */
internal fun parseTavilyDate(value: String?): LocalDate? =
    value?.let {
        try {
            ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate()
        } catch (e: DateTimeParseException) {
            null
        }
    }
