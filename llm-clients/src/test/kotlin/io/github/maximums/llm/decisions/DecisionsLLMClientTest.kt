package io.github.maximums.llm.decisions

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClientException
import de.infix.testBalloon.framework.core.testSuite
import io.github.maximums.llm.RecordingServer
import io.github.maximums.llm.decisions.DecisionModels.GEMINI_DECISION_1
import io.github.maximums.llm.decisions.DecisionQuestion.Choice
import io.github.maximums.llm.decisions.DecisionQuestion.Noul
import io.github.maximums.llm.decisions.DecisionQuestion.Score
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val QUESTIONS = DecisionParams(
    mapOf(
        "refund" to Noul("Does the customer ask for a refund?", trueDescription = "They want money back"),
        "team" to Choice("Which team should handle it?", mapOf("billing" to "Payments", "tech" to "Outages")),
        "clarity" to Score("How clear is the message?", listOf("Unclear", "Somewhat clear", "Clear")),
    )
)

/** Answers in the shape of the create-decision reference's examples. */
private const val ANSWERS = """
{
  "refund": {"type": "noul", "noul": 0.97, "confidence": 0.97},
  "team": {"type": "choice", "choice": "billing", "probabilities": {"billing": 0.9, "tech": 0.1}, "confidence": 0.9},
  "clarity": {
    "type": "score", "score": 1.8, "probabilities": {"0": 0.05, "1": 0.1, "2": 0.85},
    "legend": {"0": "Unclear", "1": "Somewhat clear", "2": "Clear"}, "confidence": 0.85
  }
}
"""

private const val REPLY = """
{
  "answers": $ANSWERS,
  "usage": {"total_input_tokens": 12, "total_output_tokens": 0, "total_tokens": 12}
}
"""

private fun client(server: RecordingServer) =
    DecisionsLLMClient("gemini-test", httpClientFactory = KtorKoogHttpClient.Factory(server.http))

val DecisionsLLMClientTest by testSuite {

    test("sends the questions about the prompt to the Decisions endpoint and returns the answers as JSON") {
        val server = RecordingServer(REPLY)

        val answer = client(server).execute(prompt("ticket", QUESTIONS) { user("I was charged twice.") }, GEMINI_DECISION_1)

        assertEquals(Json.parseToJsonElement(ANSWERS), Json.parseToJsonElement(answer.textContent()))
        val answers = mapOf(
            "refund" to DecisionAnswer.Noul(probability = 0.97, confidence = 0.97),
            "team" to DecisionAnswer.Choice("billing", mapOf("billing" to 0.9, "tech" to 0.1), confidence = 0.9),
            "clarity" to DecisionAnswer.Score(
                score = 1.8,
                probabilities = mapOf(0 to 0.05, 1 to 0.1, 2 to 0.85),
                legend = mapOf(0 to "Unclear", 1 to "Somewhat clear", 2 to "Clear"),
                confidence = 0.85,
            ),
        )
        assertEquals(answers, answer.decisionAnswers())
        assertNull(answer.finishReason)
        assertEquals(listOf(12, 0, 12), answer.metaInfo.run { listOf(inputTokensCount, outputTokensCount, totalTokensCount) })
        val request = server.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/decisions", request.url.toString())
        assertEquals("gemini-test", request.headers["x-goog-api-key"])
        val expected = """
            {
              "model": "gemini-decision-1",
              "input": "I was charged twice.",
              "questions": {
                "refund": {
                  "type": "noul",
                  "instructions": "Does the customer ask for a refund?",
                  "criteria": {"true_description": "They want money back"}
                },
                "team": {
                  "type": "choice",
                  "instructions": "Which team should handle it?",
                  "criteria": {"billing": "Payments", "tech": "Outages"}
                },
                "clarity": {
                  "type": "score",
                  "instructions": "How clear is the message?",
                  "criteria": ["Unclear", "Somewhat clear", "Clear"]
                }
              }
            }
        """
        assertEquals(Json.parseToJsonElement(expected), server.sentJson())
    }

    test("sends only the last user message's text as the input") {
        val server = RecordingServer(REPLY)
        val prompt = prompt("chat", QUESTIONS) {
            system("Support desk.")
            user("I was charged twice.")
            assistant("Sorry about that!")
            user("Please fix it.")
        }

        client(server).execute(prompt, GEMINI_DECISION_1)

        assertEquals(JsonPrimitive("Please fix it."), server.sentJson()["input"])
    }

    test("ignores tools, since the API takes none") {
        val server = RecordingServer(REPLY)
        val tool = ToolDescriptor(name = "lookup", description = "Looks something up.")

        client(server).execute(prompt("ticket", QUESTIONS) { user("Hi") }, GEMINI_DECISION_1, listOf(tool))

        assertEquals(setOf("model", "input", "questions"), server.sentJson().keys)
    }

    test("returns no answers and the block reason when the input was blocked") {
        val server = RecordingServer("""{"answers": {}, "prompt_feedback": {"block_reason": "safety"}}""")

        val answer = client(server).execute(prompt("ticket", QUESTIONS) { user("...") }, GEMINI_DECISION_1)

        assertEquals("{}", answer.textContent())
        assertEquals(emptyMap(), answer.decisionAnswers())
        assertEquals("safety", answer.finishReason)
    }

    test("reports Google's error in both of its documented shapes") {
        val interactions = RecordingServer(
            """{"error": {"code": "invalid_request", "message": "Bad question"}}""",
            HttpStatusCode.BadRequest,
        )
        val generateContent = RecordingServer(
            """{"error": {"code": 429, "message": "Quota exceeded", "status": "RESOURCE_EXHAUSTED"}}""",
            HttpStatusCode.TooManyRequests,
        )

        val invalid = assertFailsWith<LLMClientException> {
            client(interactions).execute(prompt("ticket", QUESTIONS) { user("Hi") }, GEMINI_DECISION_1)
        }
        val exhausted = assertFailsWith<LLMClientException> {
            client(generateContent).execute(prompt("ticket", QUESTIONS) { user("Hi") }, GEMINI_DECISION_1)
        }

        assertContains(invalid.message.orEmpty(), "Gemini Decisions error invalid_request (HTTP 400): Bad question")
        assertContains(exhausted.message.orEmpty(), "Gemini Decisions error RESOURCE_EXHAUSTED (HTTP 429): Quota exceeded")
    }

    test("needs DecisionParams and a user message with text, before sending anything") {
        val server = RecordingServer(REPLY)

        assertFailsWith<IllegalArgumentException> { client(server).execute(prompt("ticket") { user("Hi") }, GEMINI_DECISION_1) }
        assertFailsWith<IllegalArgumentException> {
            client(server).execute(prompt("ticket", QUESTIONS) { system("Support desk.") }, GEMINI_DECISION_1)
        }
        assertEquals(emptyList(), server.requests)
    }
}
