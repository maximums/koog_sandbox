package io.github.maximums.decision

import arrow.core.right
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.web.ApiError
import io.github.maximums.web.RecordingServer
import io.github.maximums.web.Reply
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Ollama sent back for a billing message (trimmed), with `legend` and `usage` the client doesn't read. */
private const val ANSWERS = """
{
  "model": "hf.co/ggml-org/Kev-4B-GGUF:Q4_K_M",
  "answers": {
    "billing": {"type": "noul", "noul": 0.76},
    "department": {"type": "choice", "choice": "billing", "probabilities": {"billing": 0.92, "technical": 0.06, "sales": 0.02}, "confidence": 0.87},
    "urgency": {"type": "score", "score": 1.82, "legend": {"0": "Not urgent", "1": "Somewhat urgent", "2": "Very urgent"}, "probabilities": {"0": 0.03, "1": 0.13, "2": 0.84}, "confidence": 0.73}
  },
  "usage": {"input_tokens": 149, "output_tokens": 0}
}
"""

private const val MESSAGE = "I was charged twice this month. Please fix it today."

private val billing = Noul("The message is about a billing problem.")
private val department = Choice("Which team should handle this", mapOf("billing" to "Payments and refunds", "technical" to null, "sales" to null))
private val urgency = Score("How urgent the request is", listOf("Not urgent", "Somewhat urgent", "Very urgent"))

val SystemOneClientTest by testSuite {
    fun server(reply: Reply = Reply(body = ANSWERS)) = RecordingServer(reply)

    fun RecordingServer.kev() = SystemOneClient.kev(http)

    test("sends the state and each question in the API's shape, without a key for Kev") {
        val server = server()
        val state = buildJsonObject { put("message", MESSAGE) }
        server.kev().ask(state, mapOf("billing" to billing, "department" to department, "urgency" to urgency))

        val request = server.requests.single()
        assertEquals("http://localhost:11434/v1/systemone", request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization])
        assertEquals(
            Json.parseToJsonElement(
                """
                {
                  "model": "hf.co/ggml-org/Kev-4B-GGUF:Q4_K_M",
                  "state": {"message": "$MESSAGE"},
                  "questions": {
                    "billing": {"type": "noul", "instructions": "The message is about a billing problem."},
                    "department": {"type": "choice", "instructions": "Which team should handle this", "criteria": {"billing": "Payments and refunds", "technical": null, "sales": null}},
                    "urgency": {"type": "score", "instructions": "How urgent the request is", "criteria": ["Not urgent", "Somewhat urgent", "Very urgent"]}
                  }
                }
                """
            ),
            server.sentJson(),
        )
    }

    test("sends what a yes and a no mean as a Noul's criteria") {
        val server = server(Reply(body = """{"answers": {"q": {"type": "noul", "noul": 0.9}}}"""))
        server.kev().ask(MESSAGE, mapOf("q" to Noul("Is this a complaint?", yesMeans = "It complains.", noMeans = "It doesn't.")))

        val criteria = server.sentJson().getValue("questions").jsonObject.getValue("q").jsonObject.getValue("criteria")
        assertEquals(Json.parseToJsonElement("""{"true": "It complains.", "false": "It doesn't."}"""), criteria)
    }

    test("leaves out a meaning the Noul doesn't give") {
        val server = server(Reply(body = """{"answers": {"q": {"type": "noul", "noul": 0.9}}}"""))
        server.kev().ask(MESSAGE, mapOf("q" to Noul("Is this a complaint?", yesMeans = "It complains.")))

        val criteria = server.sentJson().getValue("questions").jsonObject.getValue("q").jsonObject.getValue("criteria")
        assertEquals(Json.parseToJsonElement("""{"true": "It complains."}"""), criteria)
    }

    test("sends Jev's requests to TypeSafe with the API key") {
        val server = server()
        SystemOneClient.jev(server.http, apiKey = "ts-test").ask(MESSAGE, mapOf("billing" to billing))

        val request = server.requests.single()
        assertEquals("https://api.typesafe.ai/v1/systemone", request.url.toString())
        assertEquals("Bearer ts-test", request.headers[HttpHeaders.Authorization])
        assertEquals("jev-latest", server.sentJson().getValue("model").toString().trim('"'))
    }

    test("reads each answer as its question's type") {
        val answers = server().kev().ask(MESSAGE, mapOf("billing" to billing, "department" to department, "urgency" to urgency))

        assertEquals(
            mapOf(
                "billing" to NoulAnswer(0.76),
                "department" to ChoiceAnswer("billing", mapOf("billing" to 0.92, "technical" to 0.06, "sales" to 0.02), 0.87),
                "urgency" to ScoreAnswer(1.82, listOf(0.03, 0.13, 0.84), 0.73),
            ).right(),
            answers,
        )
    }

    test("types the answers by question, so a batch of Nouls gives NoulAnswers") {
        val server = server(Reply(body = """{"answers": {"a": {"type": "noul", "noul": 0.1}, "b": {"type": "noul", "noul": 0.9}}}"""))

        val answers: Map<String, NoulAnswer> = assertNotNull(
            server.kev().ask(MESSAGE, mapOf("a" to Noul("A?"), "b" to Noul("B?"))).getOrNull()
        )

        assertEquals(listOf(0.1, 0.9), answers.values.map { it.probability })
    }

    test("gives a Score level the model left out a probability of 0") {
        val server = server(Reply(body = """{"answers": {"q": {"type": "score", "score": 1.0, "probabilities": {"1": 1.0}, "confidence": 1.0}}}"""))

        val answer = server.kev().ask(MESSAGE, mapOf("q" to urgency)).getOrNull()?.getValue("q")

        assertEquals(listOf(0.0, 1.0, 0.0), answer?.probabilities)
    }

    test("answers no questions without calling the model") {
        val server = server()

        assertEquals(emptyMap<String, Answer>().right(), server.kev().ask(MESSAGE, emptyMap<String, Question<Answer>>()))
        assertTrue(server.requests.isEmpty())
    }

    test("reports a missing, mistyped or incomplete answer, or a reply without answers, as unexpected") {
        val replies = listOf(
            """{"answers": {}}""",
            """{"answers": {"q": {"type": "choice", "choice": "billing", "probabilities": {}, "confidence": 1.0}}}""",
            """{"answers": {"q": {"type": "noul"}}}""",
            """{"answers": {"q": {"type": "rank", "noul": 0.5}}}""",
            """{"error": "model is loading"}""",
        )
        for (body in replies) {
            val error = server(Reply(body = body)).kev().ask(MESSAGE, mapOf("q" to billing)).leftOrNull()

            assertIs<ApiError.Unexpected>(error, body)
        }
    }

    test("turns error statuses into API errors, keeping the server's message") {
        val expected = mapOf(
            400 to ApiError.InvalidRequest::class,
            401 to ApiError.Unauthorized::class,
            404 to ApiError.InvalidRequest::class,
            413 to ApiError.InvalidRequest::class,
            415 to ApiError.InvalidRequest::class,
            418 to ApiError.Unexpected::class,
            422 to ApiError.InvalidRequest::class,
            429 to ApiError.RateLimited::class,
            500 to ApiError.Unavailable::class,
            529 to ApiError.Unavailable::class,
        )
        for ((status, type) in expected) {
            val server = server(Reply(HttpStatusCode.fromValue(status), """{"error": "model 'kev' not found"}"""))

            val error = assertNotNull(server.kev().ask(MESSAGE, mapOf("q" to billing)).leftOrNull(), "HTTP $status")

            assertEquals(type, error::class, "HTTP $status")
            assertContains(error.message, "HTTP $status")
            assertContains(error.message, "model 'kev' not found")
        }
    }

    test("reports the model as unavailable when it can't be reached") {
        val unreachable = HttpClient(MockEngine { throw IOException("Connection refused") }) {
            install(ContentNegotiation) { json() }
        }

        val error = SystemOneClient.kev(unreachable).ask(MESSAGE, mapOf("q" to billing)).leftOrNull()

        assertIs<IOException>(assertIs<ApiError.Unavailable>(error).cause)
    }

    test("rejects a Choice or Score of the wrong size, or a blank model or key, up front") {
        val http = server().http
        assertFailsWith<IllegalArgumentException> { Choice("Pick", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Choice("Pick", (1..256).associate { "option $it" to null }) }
        assertFailsWith<IllegalArgumentException> { Score("Rate", emptyList()) }
        assertFailsWith<IllegalArgumentException> { Score("Rate", (1..11).map { "level $it" }) }
        assertFailsWith<IllegalArgumentException> { SystemOneClient.kev(http, model = " ") }
        assertFailsWith<IllegalArgumentException> { SystemOneClient.jev(http, apiKey = " ") }
    }
}
