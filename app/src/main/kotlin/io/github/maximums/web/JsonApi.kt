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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// How the JSON web APIs we call are called and fail, whatever the service.

/**
 * Posts [request] as JSON to [url] and reads the JSON reply, sending [apiKey] as a bearer token when given.
 * Every expected failure is raised as an [ApiError]: [service] can't be reached, it answers with an error
 * status (turned into an error by [toError]), or its reply can't be read.
 */
internal suspend inline fun <reified Req : Any, reified Res> Raise<ApiError>.postJson(
    http: HttpClient,
    url: String,
    apiKey: String?,
    request: Req,
    service: String,
    toError: (HttpResponse) -> ApiError,
): Res {
    val response = try {
        http.post(url) {
            apiKey?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    } catch (e: IOException) {
        raise(ApiError.Unavailable("$service could not be reached: ${e.message}", e))
    }

    ensure(response.status.isSuccess()) { toError(response) }

    return try {
        response.body<Res>()
    } catch (e: ContentConvertException) {
        raise(ApiError.Unexpected("$service sent a response that could not be read: ${e.message}", e))
    }
}

internal suspend fun HttpResponse.errorDetails(): String = "(HTTP ${status.value}): ${bodyAsText().take(300)}"

/** `Retry-After` given as whole seconds, when the response has it. */
internal fun HttpResponse.retryAfter(): Duration? =
    headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds
