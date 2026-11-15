package io.github.maximums.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal const val TAVILY_ERROR_BODY = """{"detail":{"error":"Tavily says no."}}"""

internal data class Reply(
    val status: HttpStatusCode = HttpStatusCode.OK,
    val body: String,
    val retryAfter: String? = null,
)

/** A stand-in for a JSON web API: answers every request with [reply] and records the requests it got. */
internal class RecordingServer(reply: Reply) {
    val requests = mutableListOf<HttpRequestData>()

    val http = HttpClient(MockEngine { request ->
        requests += request
        val headers = listOfNotNull(
            HttpHeaders.ContentType to listOf("application/json"),
            reply.retryAfter?.let { HttpHeaders.RetryAfter to listOf(it) },
        )
        respond(reply.body, reply.status, headersOf(*headers.toTypedArray()))
    }) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    /** The JSON body of the only request sent. */
    fun sentJson(): JsonObject = Json.parseToJsonElement((requests.single().body as TextContent).text).jsonObject
}
