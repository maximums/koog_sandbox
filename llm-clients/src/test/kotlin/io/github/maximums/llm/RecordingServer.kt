package io.github.maximums.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.DefaultClientSSESession
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.sse.SSEClientContent
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A stand-in for a web API: answers every request with [body], [status] and [contentType] and records the requests.
 * It also serves Server-Sent Events, which Ktor's [MockEngine] can't on its own.
 */
internal class RecordingServer(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: String = "application/json",
) {
    val requests = mutableListOf<HttpRequestData>()

    // Ktor marks its SSE session types internal; real engines build the session, so the mock has to as well.
    @OptIn(InternalAPI::class)
    val http = HttpClient(SseMockEngine(MockEngineConfig().apply {
        addHandler { request ->
            requests += request
            val response = respond(body, status, headersOf(HttpHeaders.ContentType, contentType))
            (request.body as? SSEClientContent)
                ?.takeIf { status.isSuccess() }
                ?.let { response.withBody(DefaultClientSSESession(it, ByteReadChannel(body))) }
                ?: response
        }
    }))

    /** The JSON body of the only request sent; an SSE request wraps it. */
    fun sentJson(): JsonObject {
        val body = requests.single().body.let { (it as? OutgoingContent.ContentWrapper)?.delegate() ?: it }
        return Json.parseToJsonElement((body as TextContent).text).jsonObject
    }
}

/** Ktor's [MockEngine], plus the SSE support it leaves out; real engines advertise it. */
private class SseMockEngine(config: MockEngineConfig) : MockEngine(config) {
    override val supportedCapabilities: Set<HttpClientEngineCapability<out Any>> = super.supportedCapabilities + SSECapability
}

/** The response with another body: real engines hand an SSE request's body over as an SSE session. */
private fun HttpResponseData.withBody(body: Any) = HttpResponseData(statusCode, requestTime, headers, version, body, callContext)
